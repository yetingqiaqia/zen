/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.cloudml.zen.ml

import breeze.linalg._
import scala.reflect.ClassTag
import org.apache.spark.HashPartitioner
import org.apache.spark.graphx._
import org.apache.spark.graphx.impl.GraphImpl
import org.apache.spark.storage.StorageLevel

object VMBLPPartitioner {
  /**
   * Modified Balanced Label Propogation, see:
   * https://code.facebook.com/posts/274771932683700/large-scale-graph-partitioning-with-apache-giraph/
   * This is the vertex-cut version (MBLP is an edge-cut algorithm for Apache Giraph)
   */
  def partitionByVMBLP[VD: ClassTag, ED: ClassTag](
      inGraph: Graph[VD, ED],
      numIter: Int,
      storageLevel: StorageLevel): Graph[VD, ED] = {

    val numPartitions = inGraph.edges.partitions.length
    var tbrGraph = inGraph
    tbrGraph.persist(storageLevel)

    for (i <- 0 to numIter) {
      val pidRdd = tbrGraph.vertices.mapPartitionsWithIndex((pid, iter) => iter.map(t => (t._1, pid)), true)
      val pidVertices = VertexRDD(pidRdd)  // Get Vertices which v.attr = <partitionId of v>

      val pidGraph = GraphImpl(pidVertices, tbrGraph.edges)
      val neiVecVertices = pidGraph.aggregateMessages[Vector[Int]](ectx => {
        val bsvSrc = SparseVector.zeros[Int](numPartitions)
        bsvSrc(ectx.dstAttr) = 1
        val bsvDst = SparseVector.zeros[Int](numPartitions)
        bsvDst(ectx.srcAttr) = 1
        ectx.sendToSrc(bsvSrc)
        ectx.sendToDst(bsvDst)
      }, _ += _)  // Get Vertices which v.attr = Array[d0, d1, ..., dn]

      val wantVertices = neiVecVertices.mapValues(discreteSample)
      // Get Vertices which v.attr = (<partitionId now>, <partitionId to move to>) 
      val moveVertices = pidVertices.innerZipJoin(wantVertices)((_, fromPid, toPid) => (fromPid, toPid))

      // Get a matrix which mat(i)(j) = total num of vertices that want to move from i to j
      val moveMat = moveVertices.aggregate(CSCMatrix.zeros[Int](numPartitions, numPartitions))({
        case (mat, (_, ft)) => {
          if(ft._1 != ft._2) mat(ft._1, ft._2) += 1
          mat
      }}, _ += _)

      val newPidRdd = moveVertices.mapPartitions(iter => iter.map({case (vid, (from, to)) => {
        if(from == to) (vid, from)
        else {
          // Move vertices under balance constraints
          val numOut = moveMat(from, to)
          val numIn = moveMat(to, from)
          val threshold = math.min(numOut, numIn)
          val r = threshold.asInstanceOf[Float] / numOut
          val u = math.random
          if(u < r) (vid, to)
          else (vid, from)
        }
      }}))
      val newPidVertices = VertexRDD(newPidRdd)  // Get Vertices which v.attr = <partitionId after moving>

      // Repartition
      val newPidGraph = GraphImpl(newPidVertices, tbrGraph.edges)
      val tempEdges = newPidGraph.triplets.mapPartitions(iter => iter.map{
        et => (et.srcAttr, Edge(et.srcId, et.dstId, et.attr))
      })
      val newEdges = tempEdges.partitionBy(new HashPartitioner(numPartitions)).map(_._2)

      val ntbrGraph = GraphImpl(tbrGraph.vertices, newEdges, null.asInstanceOf[VD], storageLevel, storageLevel)
      ntbrGraph.persist(storageLevel)
      tbrGraph.unpersist(false)
      tbrGraph = ntbrGraph
    }  // End for
    tbrGraph
  }

  val discreteSample: Vector[Int] => Int = (dist) => {
    val s = sum(dist)
    val u = math.random * s
    dist.activeIterator.scanLeft((-1, 0)){case ((li, ps), (i, p)) => (i, ps + p)}
      .dropWhile(_._2 <= u).next()._1
  }
}
