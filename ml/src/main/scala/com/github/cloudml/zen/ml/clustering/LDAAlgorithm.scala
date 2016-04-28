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

package com.github.cloudml.zen.ml.clustering

import java.lang.ref.SoftReference
import java.util.Random
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.{ConcurrentLinkedQueue, Executors}
import scala.collection.JavaConversions._
import scala.concurrent._
import scala.concurrent.duration._

import LDADefines._
import com.github.cloudml.zen.ml.sampler._
import com.github.cloudml.zen.ml.util.XORShiftRandom

import breeze.linalg.{DenseVector => BDV, SparseVector => BSV, sum, convert}
import org.apache.spark.graphx2._
import org.apache.spark.graphx2.impl.{EdgePartition, GraphImpl}


abstract class LDAAlgorithm extends Serializable {
  def sampleGraph(corpus: Graph[TC, TA],
    topicCounters: BDV[Count],
    seed: Int,
    sampIter: Int,
    numTokens: Long,
    numTopics: Int,
    numTerms: Int,
    alpha: Double,
    alphaAS: Double,
    beta: Double): GraphImpl[TC, TA] = {
    val graph = refreshEdgeAssociations(corpus)
    val edges = graph.edges
    val vertices = graph.vertices
    val conf = edges.context.getConf
    val numThreads = conf.getInt(cs_numThreads, 1)
    val accelMethod = conf.get(cs_accelMethod, "alias")
    val numPartitions = edges.partitions.length
    val spf = samplePartition(numThreads, accelMethod, numPartitions, sampIter, seed,
      topicCounters, numTokens, numTopics, numTerms, alpha, alphaAS, beta)_
    val partRDD = edges.partitionsRDD.mapPartitions(_.map(Function.tupled((pid, ep) => {
      val startedAt = System.nanoTime()
      val result = spf(pid, ep)
      val elapsedSeconds = (System.nanoTime() - startedAt) / 1e9
      println(s"Partition sampling $sampIter takes: $elapsedSeconds secs")
      (pid, result)
    })), preservesPartitioning=true)
    GraphImpl.fromExistingRDDs(vertices, edges.withPartitionsRDD(partRDD))
  }

  def updateVertexCounters(sampledCorpus: Graph[TC, TA],
    numTopics: Int,
    inferenceOnly: Boolean = false): GraphImpl[TC, TA] = {
    val dscp = numTopics >> 3
    val graph = sampledCorpus.asInstanceOf[GraphImpl[TC, TA]]
    val vertices = graph.vertices
    val edges = graph.edges
    val conf = edges.context.getConf
    val numThreads = conf.getInt(cs_numThreads, 1)
    val cpf = countPartition(numThreads, numTopics, inferenceOnly)_
    val shippedCounters = edges.partitionsRDD.mapPartitions(_.flatMap(Function.tupled((_, ep) =>
      cpf(ep)
    ))).partitionBy(vertices.partitioner.get)

    // Below identical map is used to isolate the impact of locality of CheckpointRDD
    val isoRDD = vertices.partitionsRDD.mapPartitions(iter => iter, preservesPartitioning=true)
    val partRDD = isoRDD.zipPartitions(shippedCounters, preservesPartitioning=true)(
      (svpIter, cntsIter) => svpIter.map(svp => {
        val results = svp.values
        val index = svp.index
        val marks = new AtomicIntegerArray(results.length)
        implicit val es = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(numThreads))
        val all = cntsIter.grouped(numThreads * 5).map(batch => Future {
          batch.foreach(Function.tupled((vid, counter) => {
            val i = index.getPos(vid)
            if (marks.getAndDecrement(i) == 0) {
              results(i) = counter
            } else {
              while (marks.getAndSet(i, -1) <= 0) {}
              val agg = results(i)
              results(i) = if (isTermId(vid)) agg match {
                case u: BDV[Count] => counter match {
                  case v: BDV[Count] => u :+= v
                  case v: BSV[Count] => u :+= v
                }
                case u: BSV[Count] => counter match {
                  case v: BDV[Count] => v :+= u
                  case v: BSV[Count] =>
                    u :+= v
                    if (u.activeSize >= dscp) toBDV(u) else u
                }
              } else agg match {
                case u: BSV[Count] => counter match {
                  case v: BSV[Count] => u :+= v
                }
              }
            }
            marks.set(i, Int.MaxValue)
          }))
        })
        Await.ready(Future.sequence(all), 1.hour)
        es.shutdown()
        svp.withValues(results)
      })
    )
    GraphImpl.fromExistingRDDs(vertices.withPartitionsRDD(partRDD), edges)
  }

  def calcPerplexity(corpus: Graph[TC, TA],
    topicCounters: BDV[Count],
    numTokens: Long,
    numTopics: Int,
    numTerms: Int,
    alpha: Double,
    alphaAS: Double,
    beta: Double): LDAPerplexity = {
    val refrGraph = refreshEdgeAssociations(corpus)
    val edges = refrGraph.edges
    val numThreads = edges.context.getConf.getInt(cs_numThreads, 1)
    val ppf = perplexPartition(numThreads, topicCounters, numTokens, numTopics, numTerms, alpha, alphaAS, beta)_
    val sumPart = edges.partitionsRDD.mapPartitions(_.map(Function.tupled((_, ep) =>
      ppf(ep)
    )))
    val (llht, wllht, dllht) = sumPart.collect().unzip3
    val pplx = math.exp(-llht.par.sum / numTokens)
    val wpplx = math.exp(-wllht.par.sum / numTokens)
    val dpplx = math.exp(-dllht.par.sum / numTokens)
    new LDAPerplexity(pplx, wpplx, dpplx)
  }

  def samplePartition(numThreads: Int,
    accelMethod: String,
    numPartitions: Int,
    sampIter: Int,
    seed: Int,
    topicCounters: BDV[Count],
    numTokens: Long,
    numTopics: Int,
    numTerms: Int,
    alpha: Double,
    alphaAS: Double,
    beta: Double)
    (pid: Int, ep: EdgePartition[TA, TC]): EdgePartition[TA, TC]

  def countPartition(numThreads: Int,
    numTopics: Int,
    inferenceOnly: Boolean)
    (ep: EdgePartition[TA, TC]): Iterator[(VertexId, TC)]

  def perplexPartition(numThreads: Int,
    topicCounters: BDV[Count],
    numTokens: Long,
    numTopics: Int,
    numTerms: Int,
    alpha: Double,
    alphaAS: Double,
    beta: Double)
    (ep: EdgePartition[TA, TC]): (Double, Double, Double)

  def resetDist_abDense(ab: DiscreteSampler[Double],
    alphak_denoms: BDV[Double],
    beta: Double): DiscreteSampler[Double] = {
    val probs = alphak_denoms.copy :*= beta
    ab.resetDist(probs.data, null, probs.length)
  }

  @inline def sum_abDense(alphak_denoms: BDV[Double],
    beta: Double): Double = {
    sum(alphak_denoms.copy :*= beta)
  }

  def calc_denoms(topicCounters: BDV[Count],
    betaSum: Double): BDV[Double] = {
    val k = topicCounters.length
    val bdv = BDV.zeros[Double](k)
    var i = 0
    while (i < k) {
      bdv(i) = 1.0 / (topicCounters(i) + betaSum)
      i += 1
    }
    bdv
  }

  @inline def calc_alphak_denoms(denoms: BDV[Double],
    alphaAS: Double,
    betaSum: Double,
    alphaRatio: Double): BDV[Double] = {
    (denoms.copy :*= ((alphaAS - betaSum) * alphaRatio)) :+= alphaRatio
  }
}

abstract class LDAWordByWord extends LDAAlgorithm {
  override def countPartition(numThreads: Int,
    numTopics: Int,
    inferenceOnly: Boolean)
    (ep: EdgePartition[TA, TC]): Iterator[(VertexId, TC)] = {
    val dscp = numTopics >>> 3
    val totalSize = ep.size
    val lcSrcIds = ep.localSrcIds
    val lcDstIds = ep.localDstIds
    val l2g = ep.local2global
    val vattrs = ep.vertexAttrs
    val data = ep.data
    val vertSize = vattrs.length
    val results = new Array[(VertexId, TC)](vertSize)
    val marks = new AtomicIntegerArray(vertSize)

    implicit val es = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(numThreads))
    val all = Future.traverse(ep.index.iterator)(Function.tupled((_, offset) => Future {
      val si = lcSrcIds(offset)
      var termTuple = results(si)
      if (termTuple == null && !inferenceOnly) {
        termTuple = (l2g(si), BSV.zeros[Count](numTopics))
        results(si) = termTuple
      }
      var termTopics = if (!inferenceOnly) termTuple._2 else null
      var pos = offset
      while (pos < totalSize && lcSrcIds(pos) == si) {
        val di = lcDstIds(pos)
        var docTuple = results(di)
        if (docTuple == null) {
          if (marks.getAndDecrement(di) == 0) {
            docTuple = (l2g(di), BSV.zeros[Count](numTopics))
            results(di) = docTuple
            marks.set(di, Int.MaxValue)
          } else {
            while (marks.get(di) <= 0) {}
            docTuple = results(di)
          }
        }
        val docTopics = docTuple._2
        val topics = data(pos)
        var i = 0
        while (i < topics.length) {
          val topic = topics(i)
          if (!inferenceOnly) termTopics match {
            case v: BDV[Count] => v(topic) += 1
            case v: BSV[Count] =>
              v(topic) += 1
              if (v.activeSize >= dscp) {
                termTuple = (l2g(si), toBDV(v))
                results(si) = termTuple
                termTopics = termTuple._2
              }
          }
          docTopics.synchronized {
            docTopics(topic) += 1
          }
          i += 1
        }
        pos += 1
      }
    }))
    Await.ready(all, 1.hour)
    es.shutdown()
    results.iterator.filter(_ != null)
  }

  override def perplexPartition(numThreads: Int,
    topicCounters: BDV[Count],
    numTokens: Long,
    numTopics: Int,
    numTerms: Int,
    alpha: Double,
    alphaAS: Double,
    beta: Double)
    (ep: EdgePartition[TA, TC]): (Double, Double, Double) = {
    val alphaSum = alpha * numTopics
    val betaSum = beta * numTerms
    val alphaRatio = alphaSum / (numTokens + alphaAS * numTopics)
    val alphaks = (convert(topicCounters, Double) :+= alphaAS) :*= alphaRatio
    val denoms = calc_denoms(topicCounters, betaSum)
    val alphak_denoms = calc_alphak_denoms(denoms, alphaAS, betaSum, alphaRatio)
    val beta_denoms = denoms.copy :*= beta
    // \frac{{\alpha }_{k}{\beta }_{w}}{{n}_{k}+\bar{\beta }}
    val abDenseSum = sum_abDense(alphak_denoms, beta)
    val totalSize = ep.size
    val lcSrcIds = ep.localSrcIds
    val lcDstIds = ep.localDstIds
    val vattrs = ep.vertexAttrs
    val data = ep.data
    val vertSize = vattrs.length
    val doc_denoms = new Array[Double](vertSize)
    val marks = new AtomicIntegerArray(vertSize)
    @volatile var llhs = 0D
    @volatile var wllhs = 0D
    @volatile var dllhs = 0D

    implicit val es = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(numThreads))
    val all = Future.traverse(ep.index.iterator)(Function.tupled((_, offset) => Future {
      val si = lcSrcIds(offset)
      val termTopics = vattrs(si)
      val waSparseSum = sum_waSparse(alphak_denoms, termTopics)
      val sum12 = abDenseSum + waSparseSum
      val termBeta_denoms = calc_termBeta_denoms(denoms, beta_denoms, termTopics)
      var llhs_th = 0D
      var wllhs_th = 0D
      var dllhs_th = 0D
      var pos = offset
      while (pos < totalSize && lcSrcIds(pos) == si) {
        val di = lcDstIds(pos)
        val docTopics = vattrs(di).asInstanceOf[BSV[Count]]
        if (marks.get(di) == 0) {
          doc_denoms(di) = 1.0 / (sum(docTopics) + alphaSum)
          marks.set(di, 1)
        }
        val doc_denom = doc_denoms(di)
        val topics = data(pos)
        val dwbSparseSum = sum_dwbSparse(termBeta_denoms, docTopics)
        val prob = (sum12 + dwbSparseSum) * doc_denom
        llhs_th += Math.log(prob) * topics.length
        var i = 0
        while (i < topics.length) {
          val topic = topics(i)
          wllhs_th += Math.log(termBeta_denoms(topic))
          dllhs_th += Math.log((docTopics(topic) + alphaks(topic)) * doc_denom)
          i += 1
        }
        pos += 1
      }
      llhs += llhs_th
      wllhs += wllhs_th
      dllhs += dllhs_th
    }))
    Await.ready(all, 2.hour)
    es.shutdown()
    (llhs, wllhs, dllhs)
  }

  def resetDist_waSparse(wa: DiscreteSampler[Double],
    alphak_denoms: BDV[Double],
    termTopics: TC): DiscreteSampler[Double] = termTopics match {
    case v: BDV[Count] =>
      val k = v.length
      val probs = new Array[Double](k)
      val space = new Array[Int](k)
      var psize = 0
      var i = 0
      while (i < k) {
        val cnt = v(i)
        if (cnt > 0) {
          probs(psize) = alphak_denoms(i) * cnt
          space(psize) = i
          psize += 1
        }
        i += 1
      }
      wa.resetDist(probs, space, psize)
    case v: BSV[Count] =>
      val used = v.used
      val index = v.index
      val data = v.data
      val probs = new Array[Double](used)
      var i = 0
      while (i < used) {
        probs(i) = alphak_denoms(index(i)) * data(i)
        i += 1
      }
      wa.resetDist(probs, index, used)
  }

  def sum_waSparse(alphak_denoms: BDV[Double],
    termTopics: TC): Double = termTopics match {
    case v: BDV[Count] =>
      val k = v.length
      var sum = 0.0
      var i = 0
      while (i < k) {
        val cnt = v(i)
        if (cnt > 0) {
          sum += alphak_denoms(i) * cnt
        }
        i += 1
      }
      sum
    case v: BSV[Count] =>
      val used = v.used
      val index = v.index
      val data = v.data
      var sum = 0.0
      var i = 0
      while (i < used) {
        sum += alphak_denoms(index(i)) * data(i)
        i += 1
      }
      sum
  }
  
  def resetDist_dwbSparse(dwb: CumulativeDist[Double],
    termBeta_denoms: BDV[Double],
    docTopics: BSV[Count]): CumulativeDist[Double] = {
    val used = docTopics.used
    val index = docTopics.index
    val data = docTopics.data
    // DANGER operations for performance
    dwb._used = used
    val cdf = dwb._cdf
    var sum = 0.0
    var i = 0
    while (i < used) {
      sum += termBeta_denoms(index(i)) * data(i)
      cdf(i) = sum
      i += 1
    }
    dwb._space = index
    dwb
  }

  def resetDist_dwbSparse_withAdjust(dwb: CumulativeDist[Double],
    denoms: BDV[Double],
    termBeta_denoms: BDV[Double],
    docTopics: BSV[Count],
    curTopic: Int): CumulativeDist[Double] = {
    val used = docTopics.used
    val index = docTopics.index
    val data = docTopics.data
    // DANGER operations for performance
    dwb._used = used
    val cdf = dwb._cdf
    var sum = 0.0
    var i = 0
    while (i < used) {
      val topic = index(i)
      val prob = if (topic == curTopic) {
        (termBeta_denoms(topic) - denoms(topic)) * (data(i) - 1)
      } else {
        termBeta_denoms(topic) * data(i)
      }
      sum += prob
      cdf(i) = sum
      i += 1
    }
    dwb._space = index
    dwb
  }

  def sum_dwbSparse(termBeta_denoms: BDV[Double],
    docTopics: BSV[Count]): Double = {
    val used = docTopics.used
    val index = docTopics.index
    val data = docTopics.data
    var sum = 0.0
    var i = 0
    while (i < used) {
      sum += termBeta_denoms(index(i)) * data(i)
      i += 1
    }
    sum
  }

  def calc_termBeta_denoms(denoms: BDV[Double],
    beta_denoms: BDV[Double],
    termTopics: TC): BDV[Double] = {
    val bdv = beta_denoms.copy
    termTopics match {
      case v: BDV[Count] =>
        val k = v.length
        var i = 0
        while (i < k) {
          bdv(i) += denoms(i) * v(i)
          i += 1
        }
      case v: BSV[Count] =>
        val used = v.used
        val index = v.index
        val data = v.data
        var i = 0
        while (i < used) {
          val topic = index(i)
          bdv(topic) += denoms(topic) * data(i)
          i += 1
        }
    }
    bdv
  }
}

class ZenSemiLDA extends LDAWordByWord {
  override def samplePartition(numThreads: Int, // N of threads
    accelMethod: String, // Accelerating Methods for Sampling
    numPartitions: Int, // N of Partitions
    sampIter: Int,
    seed: Int,
    topicCounters: BDV[Count], //
    numTokens: Long, // N of total tokens
    numTopics: Int, // N of total topics
    numTerms: Int,
    alpha: Double,
    alphaAS: Double,
    beta: Double)
    (pid: Int, ep: EdgePartition[TA, TC]): EdgePartition[TA, TC] = {
    val alphaRatio = alpha * numTopics / (numTokens + alphaAS * numTopics) // alpha
    val betaSum = beta * numTerms
    val denoms = calc_denoms(topicCounters, betaSum)
    val alphak_denoms = calc_alphak_denoms(denoms, alphaAS, betaSum, alphaRatio)
    val beta_denoms = denoms.copy :*= beta
    val totalSize = ep.size
    val lcSrcIds = ep.localSrcIds
    val lcDstIds = ep.localDstIds
    val l2g = ep.local2global
    val vattrs = ep.vertexAttrs
    val data = ep.data
    val thq = new ConcurrentLinkedQueue(0 until numThreads)
    // table/ftree is a per term data structure
    // in GraphX, edges in a partition are clustered by source IDs (term id in this case)
    // so, use below simple cache to avoid calculating table each time
    val global: DiscreteSampler[Double] = accelMethod match {
      case "ftree" => new FTree[Double](isSparse=false)
      case "alias" | "hybrid" => new AliasTable
    }
    val gens = new Array[XORShiftRandom](numThreads)
    val termDists = new Array[DiscreteSampler[Double]](numThreads)
    val cdfDists = new Array[CumulativeDist[Double]](numThreads)
    resetDist_abDense(global, alphak_denoms, beta)

    implicit val es = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(numThreads))
    val all = Future.traverse(ep.index.iterator)(Function.tupled((_, offset) => Future {
      val thid = thq.poll()
      var gen = gens(thid)
      if (gen == null) {
        gen = new XORShiftRandom(((seed + sampIter) * numPartitions + pid) * numThreads + thid)
        gens(thid) = gen
        termDists(thid) = accelMethod match {
          case "alias" => new AliasTable[Double]
          case "ftree" | "hybrid" => new FTree(isSparse=true)
        }
        cdfDists(thid) = new CumulativeDist[Double]
        termDists(thid).reset(numTopics)
        cdfDists(thid).reset(numTopics)
      }
      val termDist = termDists(thid)
      val si = lcSrcIds(offset)
      if (!isVirtualTermId(l2g(si))) {
        val termTopics = vattrs(si)

        resetDist_waSparse(termDist, alphak_denoms, termTopics)
        val denseTermTopics = termTopics match {
          case v: BDV[Count] => v
          case v: BSV[Count] => toBDV(v)
        }
        val termBeta_denoms = calc_termBeta_denoms(denoms, beta_denoms, termTopics)
        val cdfDist = cdfDists(thid)
        var pos = offset
        while (pos < totalSize && lcSrcIds(pos) == si) {
          val di = lcDstIds(pos)
          val docTopics = vattrs(di).asInstanceOf[BSV[Count]]
          val topics = data(pos)
          val occur = topics.length
          if (occur == 1) {
            val topic = topics(0)
            resetDist_dwbSparse_withAdjust(cdfDist, denoms, termBeta_denoms, docTopics, topic)
            topics(0) = tokenSampling(gen, global, termDist, cdfDist, denseTermTopics, topic)
          } else {
            resetDist_dwbSparse(cdfDist, termBeta_denoms, docTopics)
            var i = 0
            while (i < occur) {
              val topic = topics(i)
              topics(i) = tokenResampling(gen, global, termDist, cdfDist, denseTermTopics, docTopics, topic, beta)
              i += 1
            }
          }
          pos += 1
        }
      }
      thq.add(thid)
    }))
    Await.ready(all, 2.hour)
    es.shutdown()
    ep.withoutVertexAttributes()
  }

  def tokenSampling(gen: Random,
    ab: DiscreteSampler[Double],
    wa: DiscreteSampler[Double],
    dwb: CumulativeDist[Double],
    termTopics: BDV[Count],
    topic: Int): Int = {
    val dwbSum = dwb.norm
    val sum23 = dwbSum + wa.norm
    val distSum = sum23 + ab.norm
    val genSum = gen.nextDouble() * distSum
    if (genSum < dwbSum) {
      dwb.sampleFrom(genSum, gen)
    } else if (genSum < sum23) wa match {
      case wt: AliasTable[Double] =>
        val rr = 1.0 / termTopics(topic)
        wt.resampleFrom(genSum - dwbSum, gen, topic, rr)
      case wf: FTree[Double] => wf.sampleFrom(genSum - dwbSum, gen)
    } else {
      ab.sampleFrom(genSum - sum23, gen)
    }
  }

  def tokenResampling(gen: Random,
    ab: DiscreteSampler[Double],
    wa: DiscreteSampler[Double],
    dwb: CumulativeDist[Double],
    termTopics: BDV[Count],
    docTopics: BSV[Count],
    topic: Int,
    beta: Double): Int = {
    val dwbSum = dwb.norm
    val sum23 = dwbSum + wa.norm
    val distSum = sum23 + ab.norm
    val genSum = gen.nextDouble() * distSum
    if (genSum < dwbSum) {
      val nkd = docTopics(topic)
      val nkw_beta = termTopics(topic) + beta
      val rr = 1.0 / nkd + 1.0 / nkw_beta - 1.0 / nkd / nkw_beta
      dwb.resampleFrom(genSum, gen, topic, rr)
    } else if (genSum < sum23) wa match {
      case wt: AliasTable[Double] =>
        val rr = 1.0 / termTopics(topic)
        wt.resampleFrom(genSum - dwbSum, gen, topic, rr)
      case wf: FTree[Double] => wf.sampleFrom(genSum - dwbSum, gen)
    } else {
      ab.sampleFrom(genSum - sum23, gen)
    }
  }
}

class ZenLDA extends LDAWordByWord {
  override def samplePartition(numThreads: Int,
    accelMethod: String,
    numPartitions: Int,
    sampIter: Int,
    seed: Int,
    topicCounters: BDV[Count],
    numTokens: Long,
    numTopics: Int,
    numTerms: Int,
    alpha: Double,
    alphaAS: Double,
    beta: Double)
    (pid: Int, ep: EdgePartition[TA, TC]): EdgePartition[TA, TC] = {
    val alphaRatio = alpha * numTopics / (numTokens + alphaAS * numTopics)
    val betaSum = beta * numTerms
    val denoms = calc_denoms(topicCounters, betaSum)
    val alphak_denoms = calc_alphak_denoms(denoms, alphaAS, betaSum, alphaRatio)
    val beta_denoms = denoms.copy :*= beta
    val totalSize = ep.size
    val lcSrcIds = ep.localSrcIds
    val lcDstIds = ep.localDstIds
    val vattrs = ep.vertexAttrs
    val data = ep.data
    val thq = new ConcurrentLinkedQueue(0 until numThreads)
    // table/ftree is a per term data structure
    // in GraphX, edges in a partition are clustered by source IDs (term id in this case)
    // so, use below simple cache to avoid calculating table each time
    val global: DiscreteSampler[Double] = accelMethod match {
      case "ftree" => new FTree[Double](isSparse=false)
      case "alias" | "hybrid" => new AliasTable
    }
    val gens = new Array[XORShiftRandom](numThreads)
    val termDists = new Array[DiscreteSampler[Double]](numThreads)
    val cdfDists = new Array[CumulativeDist[Double]](numThreads)
    resetDist_abDense(global, alphak_denoms, beta)

    implicit val es = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(numThreads))
    val all = Future.traverse(ep.index.iterator)(Function.tupled((_, offset) => Future {
      val thid = thq.poll()
      var gen = gens(thid)
      if (gen == null) {
        gen = new XORShiftRandom(((seed + sampIter) * numPartitions + pid) * numThreads + thid)
        gens(thid) = gen
        termDists(thid) = accelMethod match {
          case "alias" => new AliasTable[Double]
          case "ftree" | "hybrid" => new FTree(isSparse=true)
        }
        cdfDists(thid) = new CumulativeDist[Double]
        termDists(thid).reset(numTopics)
        cdfDists(thid).reset(numTopics)
      }
      val termDist = termDists(thid)
      val si = lcSrcIds(offset)
      val termTopics = vattrs(si)
      resetDist_waSparse(termDist, alphak_denoms, termTopics)
      val denseTermTopics = termTopics match {
        case v: BDV[Count] => v
        case v: BSV[Count] => toBDV(v)
      }
      val termBeta_denoms = calc_termBeta_denoms(denoms, beta_denoms, termTopics)
      val cdfDist = cdfDists(thid)
      var pos = offset
      while (pos < totalSize && lcSrcIds(pos) == si) {
        val di = lcDstIds(pos)
        val docTopics = vattrs(di).asInstanceOf[BSV[Count]]
        val topics = data(pos)
        val occur = topics.length
        if (occur == 1) {
          val topic = topics(0)
          resetDist_dwbSparse_withAdjust(cdfDist, denoms, termBeta_denoms, docTopics, topic)
          topics(0) = tokenSampling(gen, global, termDist, cdfDist, denseTermTopics, topic)
        } else {
          resetDist_dwbSparse(cdfDist, termBeta_denoms, docTopics)
          var i = 0
          while (i < occur) {
            val topic = topics(i)
            topics(i) = tokenResampling(gen, global, termDist, cdfDist, denseTermTopics, docTopics, topic, beta)
            i += 1
          }
        }
        pos += 1
      }
      thq.add(thid)
    }))
    Await.ready(all, 2.hour)
    es.shutdown()
    ep.withoutVertexAttributes()
  }

  def tokenSampling(gen: Random,
    ab: DiscreteSampler[Double],
    wa: DiscreteSampler[Double],
    dwb: CumulativeDist[Double],
    termTopics: BDV[Count],
    topic: Int): Int = {
    val dwbSum = dwb.norm
    val sum23 = dwbSum + wa.norm
    val distSum = sum23 + ab.norm
    val genSum = gen.nextDouble() * distSum
    if (genSum < dwbSum) {
      dwb.sampleFrom(genSum, gen)
    } else if (genSum < sum23) wa match {
      case wt: AliasTable[Double] =>
        val rr = 1.0 / termTopics(topic)
        wt.resampleFrom(genSum - dwbSum, gen, topic, rr)
      case wf: FTree[Double] => wf.sampleFrom(genSum - dwbSum, gen)
    } else {
      ab.sampleFrom(genSum - sum23, gen)
    }
  }

  def tokenResampling(gen: Random,
    ab: DiscreteSampler[Double],
    wa: DiscreteSampler[Double],
    dwb: CumulativeDist[Double],
    termTopics: BDV[Count],
    docTopics: BSV[Count],
    topic: Int,
    beta: Double): Int = {
    val dwbSum = dwb.norm
    val sum23 = dwbSum + wa.norm
    val distSum = sum23 + ab.norm
    val genSum = gen.nextDouble() * distSum
    if (genSum < dwbSum) {
      val nkd = docTopics(topic)
      val nkw_beta = termTopics(topic) + beta
      val rr = 1.0 / nkd + 1.0 / nkw_beta - 1.0 / nkd / nkw_beta
      dwb.resampleFrom(genSum, gen, topic, rr)
    } else if (genSum < sum23) wa match {
      case wt: AliasTable[Double] =>
        val rr = 1.0 / termTopics(topic)
        wt.resampleFrom(genSum - dwbSum, gen, topic, rr)
      case wf: FTree[Double] => wf.sampleFrom(genSum - dwbSum, gen)
    } else {
      ab.sampleFrom(genSum - sum23, gen)
    }
  }
}

class LightLDA extends LDAWordByWord {
  override def samplePartition(numThreads: Int,
    accelMethod: String,
    numPartitions: Int,
    sampIter: Int,
    seed: Int,
    topicCounters: BDV[Count],
    numTokens: Long,
    numTopics: Int,
    numTerms: Int,
    alpha: Double,
    alphaAS: Double,
    beta: Double)
    (pid: Int, ep: EdgePartition[TA, TC]): EdgePartition[TA, TC] = {
    val alphaRatio = alpha * numTopics / (numTokens + alphaAS * numTopics)
    val betaSum = beta * numTerms
    val totalSize = ep.size
    val lcSrcIds = ep.localSrcIds
    val lcDstIds = ep.localDstIds
    val vattrs = ep.vertexAttrs
    val data = ep.data
    val vertSize = vattrs.length
    val thq = new ConcurrentLinkedQueue(0 until numThreads)

    val alphaDist = new AliasTable[Double]
    val betaDist = new AliasTable[Double]
    val docCache = new Array[SoftReference[AliasTable[Count]]](vertSize)
    val gens = new Array[XORShiftRandom](numThreads)
    val termDists = new Array[AliasTable[Double]](numThreads)
    resetDist_aDense(alphaDist, topicCounters, numTopics, alphaRatio, alphaAS)
    resetDist_bDense(betaDist, topicCounters, numTopics, beta, betaSum)
    val p = tokenTopicProb(topicCounters, beta, alpha, alphaAS, numTokens, numTerms)_
    val dPFun = docProb(topicCounters, alpha, alphaAS, numTokens)_
    val wPFun = wordProb(topicCounters, numTerms, beta)_

    implicit val es = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(numThreads))
    val all = Future.traverse(ep.index.iterator)(Function.tupled((_, offset) => Future {
      val thid = thq.poll()
      var gen = gens(thid)
      if (gen == null) {
        gen = new XORShiftRandom(((seed + sampIter) * numPartitions + pid) * numThreads + thid)
        gens(thid) = gen
        termDists(thid) = new AliasTable[Double]
        termDists(thid).reset(numTopics)
      }
      val termDist = termDists(thid)
      val si = lcSrcIds(offset)
      val termTopics = vattrs(si)
      resetDist_wSparse(termDist, topicCounters, termTopics, betaSum)
      var pos = offset
      while (pos < totalSize && lcSrcIds(pos) == si) {
        val di = lcDstIds(pos)
        val docTopics = vattrs(di)
        if (gen.nextDouble() < 1e-6) {
          resetDist_aDense(alphaDist, topicCounters, numTopics, alphaRatio, alphaAS)
          resetDist_bDense(betaDist, topicCounters, numTopics, beta, betaSum)
        }
        if (gen.nextDouble() < 1e-4) {
          resetDist_wSparse(termDist, topicCounters, termTopics, betaSum)
        }
        val docDist = dSparseCached(cache => cache == null || cache.get() == null || gen.nextDouble() < 1e-2,
          docCache, docTopics, di)

        val topics = data(pos)
        var i = 0
        while (i < topics.length) {
          var docProposal = gen.nextDouble() < 0.5
          var j = 0
          while (j < 8) {
            docProposal = !docProposal
            val topic = topics(i)
            var proposalTopic = -1
            val q = if (docProposal) {
              val aSum = alphaDist.norm
              val dPropSum = aSum + docDist.norm
              if (gen.nextDouble() * dPropSum < aSum) {
                proposalTopic = alphaDist.sampleRandom(gen)
              } else {
                val rr = 1.0 / docTopics(topic)
                proposalTopic = docDist.resampleRandom(gen, topic, rr)
              }
              dPFun
            } else {
              val wSum = termDist.norm
              val wPropSum = wSum + betaDist.norm
              val table = if (gen.nextDouble() * wPropSum < wSum) termDist else betaDist
              proposalTopic = table.sampleRandom(gen)
              wPFun
            }

            val newTopic = tokenSampling(gen, docTopics, termTopics, docProposal,
              topic, proposalTopic, q, p)
            if (newTopic != topic) {
              topics(i) = newTopic
              topicCounters(topic) -= 1
              topicCounters(newTopic) += 1
              termTopics(topic) -= 1
              termTopics(newTopic) += 1
              docTopics.synchronized {
                docTopics(topic) -= 1
                docTopics(newTopic) += 1
              }
            }
            j += 1
          }
          i += 1
        }
        pos += 1
      }
      thq.add(thid)
    }))
    Await.ready(all, 2.hour)
    es.shutdown()
    ep.withoutVertexAttributes()
  }

  /**
   * Composition of both Gibbs sampler and Metropolis Hastings sampler
   * time complexity for each sampling is: O(1)
   * 1. sampling word-related parts of standard LDA formula via Gibbs Sampler:
   * Formula (6) in Paper "LightLDA: Big Topic Models on Modest Compute Clusters":
   * ( \frac{{n}_{kd}^{-di}+{\beta }_{w}}{{n}_{k}^{-di}+\bar{\beta }} )
   * 2. given the computed probability in step 1 as proposal distribution q in Metropolis Hasting sampling,
   * and we use asymmetric dirichlet prior, presented formula (3) in Paper "Rethinking LDA: Why Priors Matter"
   * \frac{{n}_{kw}^{-di}+{\beta }_{w}}{{n}_{k}^{-di}+\bar{\beta}} \frac{{n}_{kd} ^{-di}+ \bar{\alpha}
   * \frac{{n}_{k}^{-di} + \acute{\alpha}}{\sum{n}_{k} +\bar{\acute{\alpha}}}}{\sum{n}_{kd}^{-di} +\bar{\alpha}}
   *
   * where
   * \bar{\beta}=\sum_{w}{\beta}_{w}
   * \bar{\alpha}=\sum_{k}{\alpha}_{k}
   * \bar{\acute{\alpha}}=\bar{\acute{\alpha}}=\sum_{k}\acute{\alpha}
   * {n}_{kd} is the number of tokens in doc d that belong to topic k
   * {n}_{kw} is the number of occurrence for word w that belong to topic k
   * {n}_{k} is the number of tokens in corpus that belong to topic k
   */
  def tokenSampling(gen: Random,
    docTopicCounter: TC,
    termTopicCounter: TC,
    docProposal: Boolean,
    currentTopic: Int,
    proposalTopic: Int,
    q: (TC, Int, Boolean) => Double,
    p: (TC, TC, Int, Boolean) => Double): Int = {
    if (proposalTopic == currentTopic) return proposalTopic
    val cp = p(docTopicCounter, termTopicCounter, currentTopic, true)
    val np = p(docTopicCounter, termTopicCounter, proposalTopic, false)
    val vd = if (docProposal) docTopicCounter else termTopicCounter
    val cq = q(vd, currentTopic, true)
    val nq = q(vd, proposalTopic, false)

    val pi = (np * cq) / (cp * nq)
    if (gen.nextDouble() < math.min(1.0, pi)) proposalTopic else currentTopic
  }

  // scalastyle:off
  def tokenTopicProb(totalTopicCounter: BDV[Count],
     beta: Double,
     alpha: Double,
     alphaAS: Double,
     numTokens: Long,
     numTerms: Int)(docTopicCounter: TC,
     termTopicCounter: TC,
     topic: Int,
     isAdjustment: Boolean): Double = {
    val numTopics = docTopicCounter.length
    val adjustment = if (isAdjustment) -1 else 0
    val ratio = (totalTopicCounter(topic) + adjustment + alphaAS) /
      (numTokens - 1 + alphaAS * numTopics)
    val asPrior = ratio * (alpha * numTopics)
    // constant part is removed: (docLen - 1 + alpha * numTopics)
    (termTopicCounter(topic) + adjustment + beta) *
      (docTopicCounter(topic) + adjustment + asPrior) /
      (totalTopicCounter(topic) + adjustment + (numTerms * beta))

    // original form is formula (3) in Paper: "Rethinking LDA: Why Priors Matter"
    // val docLen = brzSum(docTopicCounter)
    // (termTopicCounter(topic) + adjustment + beta) * (docTopicCounter(topic) + adjustment + asPrior) /
    //   ((totalTopicCounter(topic) + adjustment + (numTerms * beta)) * (docLen - 1 + alpha * numTopics))
  }

  // scalastyle:on

  def wordProb(totalTopicCounter: BDV[Count],
    numTerms: Int,
    beta: Double)(termTopicCounter: TC, topic: Int, isAdjustment: Boolean): Double = {
    (termTopicCounter(topic) + beta) / (totalTopicCounter(topic) + beta * numTerms)
  }

  def docProb(totalTopicCounter: BDV[Count],
    alpha: Double,
    alphaAS: Double,
    numTokens: Long)(docTopicCounter: TC, topic: Int, isAdjustment: Boolean): Double = {
    val adjustment = if (isAdjustment) -1 else 0
    val numTopics = totalTopicCounter.length
    val ratio = (totalTopicCounter(topic) + alphaAS) / (numTokens - 1 + alphaAS * numTopics)
    val asPrior = ratio * (alpha * numTopics)
    docTopicCounter(topic) + adjustment + asPrior
  }

  /**
   * \frac{{\beta}_{w}}{{n}_{k}+\bar{\beta}}
   */
  def resetDist_bDense(b: AliasTable[Double],
    topicCounters: BDV[Count],
    numTopics: Int,
    beta: Double,
    betaSum: Double): Unit = {
    val probs = new Array[Double](numTopics)
    var i = 0
    while (i < numTopics) {
      probs(i) = beta / (topicCounters(i) + betaSum)
      i += 1
    }
    b.synchronized {
      b.resetDist(probs, null, numTopics)
    }
  }

  /**
   * \frac{{n}_{kw}}{{n}_{k}+\bar{\beta}}
   */
  def resetDist_wSparse(ws: AliasTable[Double],
    topicCounters: BDV[Count],
    termTopics: TC,
    betaSum: Double): Unit = termTopics match {
    case v: BDV[Count] =>
      val numTopics = v.length
      val data = v.data
      val probs = new Array[Double](numTopics)
      val space = new Array[Int](numTopics)
      var psize = 0
      var i = 0
      while (i < numTopics) {
        val cnt = data(i)
        if (cnt > 0) {
          probs(psize) = cnt / (topicCounters(i) + betaSum)
          space(psize) = i
          psize += 1
        }
        i += 1
      }
      ws.resetDist(probs, space, psize)
    case v: BSV[Count] =>
      val used = v.used
      val index = v.index
      val data = v.data
      val probs = new Array[Double](used)
      var i = 0
      while (i < used) {
        probs(i) = data(i) / (topicCounters(index(i)) + betaSum)
        i += 1
      }
      ws.resetDist(probs, index, used)
  }

  def resetDist_aDense(a: AliasTable[Double],
    topicCounters: BDV[Count],
    numTopics: Int,
    alphaRatio: Double,
    alphaAS: Double): Unit = {
    val probs = new Array[Double](numTopics)
    var i = 0
    while (i < numTopics) {
      probs(i) = alphaRatio * (topicCounters(i) + alphaAS)
      i += 1
    }
    a.synchronized {
      a.resetDist(probs, null, numTopics)
    }
  }

  def dSparseCached(updatePred: SoftReference[AliasTable[Count]] => Boolean,
    cacheArray: Array[SoftReference[AliasTable[Count]]],
    docTopics: TC,
    lcDocId: Int): AliasTable[Count] = {
    val docCache = cacheArray(lcDocId)
    if (!updatePred(docCache)) {
      docCache.get
    } else {
      val table = AliasTable.generateAlias(docTopics)
      cacheArray(lcDocId) = new SoftReference(table)
      table
    }
  }
}

abstract class LDADocByDoc extends LDAAlgorithm {
  override def countPartition(numThreads: Int,
    numTopics: Int,
    inferenceOnly: Boolean)
    (ep: EdgePartition[TA, TC]): Iterator[(VertexId, TC)] = {
    val totalSize = ep.size
    val lcSrcIds = ep.localSrcIds
    val lcDstIds = ep.localDstIds
    val l2g = ep.local2global
    val vattrs = ep.vertexAttrs
    val data = ep.data
    val vertSize = vattrs.length
    val results = Array.tabulate(vertSize)(vi => (l2g(vi), BSV.zeros[Count](numTopics)))

    implicit val es = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(numThreads))
    val all = Future.traverse(ep.index.iterator)(Function.tupled((_, offset) => Future {
      val si = lcSrcIds(offset)
      val docTopics = results(si)._2
      var pos = offset
      while (pos < totalSize && lcSrcIds(pos) == si) {
        val di = lcDstIds(pos)
        val topics = data(pos)
        var i = 0
        while (i < topics.length) {
          val topic = topics(i)
          docTopics(topic) += 1
          if (!inferenceOnly) {
            val termTopics = results(di)._2
            termTopics.synchronized {
              termTopics(topic) += 1
            }
          }
          i += 1
        }
        pos += 1
      }
    }))
    Await.ready(all, 1.hour)
    es.shutdown()
    results.iterator.filter(_ != null)
  }

  override def perplexPartition(numThreads: Int,
    topicCounters: BDV[Count],
    numTokens: Long,
    numTopics: Int,
    numTerms: Int,
    alpha: Double,
    alphaAS: Double,
    beta: Double)
    (ep: EdgePartition[TA, TC]): (Double, Double, Double) = {
    val alphaSum = alpha * numTopics
    val betaSum = beta * numTerms
    val alphaRatio = alphaSum / (numTokens + alphaAS * numTopics)
    val alphaks = (convert(topicCounters, Double) :+= alphaAS) :*= alphaRatio
    val denoms = calc_denoms(topicCounters, betaSum)
    val alphak_denoms = calc_alphak_denoms(denoms, alphaAS, betaSum, alphaRatio)
    val abDenseSum = sum_abDense(alphak_denoms, beta)
    val totalSize = ep.size
    val lcSrcIds = ep.localSrcIds
    val lcDstIds = ep.localDstIds
    val vattrs = ep.vertexAttrs
    val data = ep.data
    @volatile var llhs = 0D
    @volatile var wllhs = 0D
    @volatile var dllhs = 0D

    implicit val es = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(numThreads))
    val all = Future.traverse(ep.index.iterator)(Function.tupled((_, offset) => Future {
      val si = lcSrcIds(offset)
      val docTopics = vattrs(si).asInstanceOf[BSV[Count]]
      val doc_denom = 1.0 / (sum(docTopics) + alphaSum)
      val nkd_denoms = calc_nkd_denoms(denoms, docTopics)
      val dbSparseSum = sum_dbSparse(nkd_denoms, beta)
      val sum12 = abDenseSum + dbSparseSum
      val docAlphaK_denoms = calc_docAlphaK_denoms(alphak_denoms, nkd_denoms)
      var llhs_th = 0D
      var wllhs_th = 0D
      var dllhs_th = 0D
      var pos = offset
      while (pos < totalSize && lcSrcIds(pos) == si) {
        val di = lcDstIds(pos)
        val termTopics = vattrs(di)
        val topics = data(pos)
        val wdaSparseSum = sum_wdaSparse(docAlphaK_denoms, termTopics)
        val prob = (sum12 + wdaSparseSum) * doc_denom
        llhs_th += Math.log(prob) * topics.length
        var i = 0
        while (i < topics.length) {
          val topic = topics(i)
          wllhs_th += Math.log((termTopics(topic) + beta) * denoms(topic))
          dllhs_th += Math.log((docTopics(topic) + alphaks(topic)) * doc_denom)
          i += 1
        }
        pos += 1
      }
      llhs += llhs_th
      wllhs += wllhs_th
      dllhs += dllhs_th
    }))
    Await.ready(all, 2.hour)
    es.shutdown()
    (llhs, wllhs, dllhs)
  }

  def resetDist_dbSparse(db: FlatDist[Double],
    nkd_denoms: BSV[Double],
    beta: Double): FlatDist[Double] = {
    val used = nkd_denoms.used
    val index = nkd_denoms.index
    val data = nkd_denoms.data
    val probs = new Array[Double](used)
    var i = 0
    while (i < used) {
      probs(i) = beta * data(i)
      i += 1
    }
    db.resetDist(probs, index, used)
  }

  def sum_dbSparse(nkd_denoms: BSV[Double],
    beta: Double): Double = {
    val used = nkd_denoms.used
    val data = nkd_denoms.data
    var sum = 0.0
    var i = 0
    while (i < used) {
      sum += beta * data(i)
      i += 1
    }
    sum
  }
  
  def resetDist_wdaSparse(wda: FlatDist[Double],
    docAlphaK_Denoms: BDV[Double],
    termTopics: TC): FlatDist[Double] = termTopics match {
    case v: BDV[Count] =>
      val k = v.length
      val probs = new Array[Double](k)
      val space = new Array[Int](k)
      var psize = 0
      var i = 0
      while (i < k) {
        val cnt = v(i)
        if (cnt > 0) {
          probs(psize) = docAlphaK_Denoms(i) * cnt
          space(psize) = i
          psize += 1
        }
        i += 1
      }
      wda.resetDist(probs, space, psize)
    case v: BSV[Count] =>
      val used = v.used
      val index = v.index
      val data = v.data
      val probs = new Array[Double](used)
      var i = 0
      while (i < used) {
        probs(i) = docAlphaK_Denoms(index(i)) * data(i)
        i += 1
      }
      wda.resetDist(probs, index, used)
  }

  def sum_wdaSparse(docAlphaK_Denoms: BDV[Double],
    termTopics: TC): Double = termTopics match {
    case v: BDV[Count] =>
      val k = v.length
      var sum = 0.0
      var i = 0
      while (i < k) {
        val cnt = v(i)
        if (cnt > 0) {
          sum += docAlphaK_Denoms(i) * cnt
        }
        i += 1
      }
      sum
    case v: BSV[Count] =>
      val used = v.used
      val index = v.index
      val data = v.data
      var sum = 0.0
      var i = 0
      while (i < used) {
        sum += docAlphaK_Denoms(index(i)) * data(i)
        i += 1
      }
      sum
  }
  
  def calc_nkd_denoms(denoms: BDV[Double],
    docTopics: BSV[Count]): BSV[Double] = {
    val used = docTopics.used
    val index = docTopics.index
    val data = docTopics.data
    val arr = new Array[Double](used)
    var i = 0
    while (i < used) {
      arr(i) = data(i) * denoms(index(i))
      i += 1
    }
    new BSV(index, arr, used, denoms.length)
  }

  def calc_docAlphaK_denoms(alphak_denoms: BDV[Double],
    nkd_denoms: BSV[Double]): BDV[Double] = {
    val bdv = alphak_denoms.copy
    val used = nkd_denoms.used
    val index = nkd_denoms.index
    val data = nkd_denoms.data
    var i = 0
    while (i < used) {
      bdv(index(i)) += data(i)
      i += 1
    }
    bdv
  }
}

class SparseLDA extends LDADocByDoc {
  override def samplePartition(numThreads: Int,
    accelMethod: String,
    numPartitions: Int,
    sampIter: Int,
    seed: Int,
    topicCounters: BDV[Count],
    numTokens: Long,
    numTopics: Int,
    numTerms: Int,
    alpha: Double,
    alphaAS: Double,
    beta: Double)
    (pid: Int, ep: EdgePartition[TA, TC]): EdgePartition[TA, TC] = {
    val alphaRatio = alpha * numTopics / (numTokens + alphaAS * numTopics)
    val betaSum = beta * numTerms
    val totalSize = ep.size
    val lcSrcIds = ep.localSrcIds
    val lcDstIds = ep.localDstIds
    val vattrs = ep.vertexAttrs
    val data = ep.data
    val thq = new ConcurrentLinkedQueue(0 until numThreads)
    val gens = new Array[XORShiftRandom](numThreads)
    val docDists = new Array[FlatDist[Double]](numThreads)
    val mainDists = new Array[FlatDist[Double]](numThreads)
    val denoms = calc_denoms(topicCounters, betaSum)
    val alphak_denoms = calc_alphak_denoms(denoms, alphaAS, betaSum, alphaRatio)
    val global = new FlatDist[Double](isSparse=false)
    resetDist_abDense(global, alphak_denoms, beta)

    implicit val es = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(numThreads))
    val all = Future.traverse(ep.index.iterator)(Function.tupled((_, offset) => Future {
      val thid = thq.poll()
      var gen = gens(thid)
      if (gen == null) {
        gen = new XORShiftRandom(((seed + sampIter) * numPartitions + pid) * numThreads + thid)
        gens(thid) = gen
        docDists(thid) = new FlatDist[Double](isSparse=true)
        mainDists(thid) = new FlatDist[Double](isSparse=true)
        docDists(thid).reset(numTopics)
        mainDists(thid).reset(numTopics)
      }
      val docDist = docDists(thid)
      val si = lcSrcIds(offset)
      val docTopics = vattrs(si).asInstanceOf[BSV[Count]]
      val nkd_denoms = calc_nkd_denoms(denoms, docTopics)
      resetDist_dbSparse(docDist, nkd_denoms, beta)
      val docAlphaK_denoms = calc_docAlphaK_denoms(alphak_denoms, nkd_denoms)
      val mainDist = mainDists(thid)
      var pos = offset
      while (pos < totalSize && lcSrcIds(pos) == si) {
        val di = lcDstIds(pos)
        val termTopics = vattrs(di)
        resetDist_wdaSparse(mainDist, docAlphaK_denoms, termTopics)
        val topics = data(pos)
        var i = 0
        while (i < topics.length) {
          topics(i) = tokenSampling(gen, global, docDist, mainDist)
          i += 1
        }
        pos += 1
      }
      thq.add(thid)
    }))
    Await.ready(all, 2.hour)
    es.shutdown()
    ep.withoutVertexAttributes()
  }

  def tokenSampling(gen: Random,
    ab: FlatDist[Double],
    db: FlatDist[Double],
    wda: FlatDist[Double]): Int = {
    val wdaSum = wda.norm
    val sum23 = wdaSum + db.norm
    val distSum = sum23 + ab.norm
    val genSum = gen.nextDouble() * distSum
    if (genSum < wdaSum) {
      wda.sampleFrom(genSum, gen)
    } else if (genSum < sum23) {
      db.sampleFrom(genSum - wdaSum, gen)
    } else {
      ab.sampleFrom(genSum - sum23, gen)
    }
  }
}
