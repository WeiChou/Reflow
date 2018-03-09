/*
 * Copyright (C) 2016-present, Wei Chou(weichou2010@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hobby.wei.c.reflow

import java.util.concurrent._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.locks.{Condition, ReentrantLock}
import hobby.chenai.nakam.basis.TAG.LogTag
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.chenai.nakam.lang.TypeBring.AsIs
import hobby.wei.c.reflow.Assist._
import hobby.wei.c.reflow.Dependency._
import hobby.wei.c.reflow.Reflow.{logger => log, _}
import hobby.wei.c.reflow.State._
import hobby.wei.c.reflow.Tracker.Runner
import hobby.wei.c.reflow.Trait.ReflowTrait
import hobby.wei.c.tool.{Locker, Snatcher}

import scala.collection._
import scala.collection.mutable.ListBuffer
import scala.util.control.Breaks._

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 26/06/2016;
  *          1.1, 31/01/2018.
  */
private[reflow] abstract class Tracker(val basis: Basis, val outer: Option[Env]) {
  private lazy final val snatcher4Init = new Snatcher
  // 这两个变量，在浏览运行阶段会根据需要自行创建（任务可能需要缓存临时参数到cache中）；
  // 而在Reinforce阶段，会从外部传入。
  // 因此有这样的设计。
  @volatile private var cacheInited: Boolean = outer.fold(false)(_.isReinforcing)
  private final lazy val reinforceCache = outer.fold(new ReinforceCache) { env =>
    if (env.isReinforcing) env.obtainCache.getOrElse(new ReinforceCache) else new ReinforceCache
  }
  private[reflow] final lazy val reinforceRequired = new AtomicBoolean(false)

  private final def getOrInitFromOuterCache(trat: String = null, sub: Option[ReinforceCache] = None): ReinforceCache = {
    if (!cacheInited) {
      snatcher4Init.tryOn {
        if (!cacheInited) {
          outer.foreach { env =>
            env.tracker.getOrInitFromOuterCache(env.trat.name$, Option(reinforceCache))
          }
          cacheInited = true
        }
      }
    }
    sub.foreach(reinforceCache.subs.putIfAbsent(trat, _))
    reinforceCache
  }

  final def getCache = getOrInitFromOuterCache()

  private[reflow] def prevOutFlow(): Out

  def getState: State.Tpe

  final def isSubReflow: Boolean = outer.isDefined

  final def isReinforceRequired: Boolean = outer.fold(reinforceRequired.get)(_.isReinforceRequired)

  final def isReinforcing: Boolean = outer.fold(getState.group == REINFORCING.group /*group代表了几个状态*/)(_.isReinforcing)

  final def requireReinforce(trat: Trait[_ <: Task]): Boolean =
    if (!outer.fold(reinforceRequired.getAndSet(true))(_.requireReinforce())) {
      val cache = getCache
      cache.begins = trat.name$ :: cache.begins
      cache.inputs = prevOutFlow()
      // 如果是并行任务的话，怎么办
      onRequireReinforce(cache)
      false
    } else true

  protected def onRequireReinforce(cache: ReinforceCache): Unit = {}

  private[reflow] def onTaskStart(trat: Trait[_]): Unit

  private[reflow] def onTaskProgress(name: String, trat: Trait[_], progress: Float, out: Out, desc: String): Unit

  private[reflow] def onTaskComplete(trat: Trait[_], out: Out, flow: Out): Unit

  /**
    * @param name    可能是从里层的任务传来的。
    * @param trigger 当前层的 Runner。
    * @param forError
    * @param trat
    * @param e
    */
  private[reflow] def performAbort(name: String, trigger: Runner, forError: Boolean, trat: Trait[_], e: Exception): Unit

  /** 先于{@link #endRunner(Runner)}执行。 */
  private[reflow] def innerError(runner: Runner, e: Exception): Unit

  private[reflow] def endRunner(runner: Runner): Unit
}

private[reflow] class ReinforceCache {
  // 用`trat.name$`作为`key`, 同一个`Reflow`中，`name$`是不能重名的，因此该方法可靠。
  /** 子`Trait`的`Task`缓存用到的`Out`。 */
  lazy val caches = new concurrent.TrieMap[String, Out]
  /** 子`Trait`的`Task`启动的`Reflow`对应的`Tracker`的`Cache`。 */
  lazy val subs = new concurrent.TrieMap[String, ReinforceCache]
  /** `reinforce`阶段开始时的`Trait.name$`。 */
  @volatile var begins: List[String] = Nil
  @volatile var inputs: Out = _
  /** 对于一个并行任务的某些子任务的`reinforce`请求，我们不打算再次执行整个并行任务，因此
    * 需要保留`浏览`运行模式的`输入`（即`reinforceInput`）和`结果`（本`reinforceOut`），以便在`reinforce`之后合并输出。 */
  @volatile var outs: Set[Key$[_]] = _

  override def toString = s"ReinforceCache:\n caches:$caches,\n subs:$subs,\n begins:$begins,\n inputs:$inputs,\n outs:$outs."
}

private[reflow] object Tracker {
  private[reflow] final class Impl(basis: Basis, traitIn: Trait[_ <: Task], transIn: immutable.Set[Transformer[_, _]], state: Scheduler.State$,
                                   feedback: Feedback, outer: Option[Env]) extends Tracker(basis: Basis, outer: Option[Env]) with Scheduler {
    private implicit lazy val lock: ReentrantLock = Locker.getLockr(this)
    private lazy val lockSync: ReentrantLock = Locker.getLockr(new AnyRef)
    private lazy val buffer4Reports = new ListBuffer[() => Unit]
    private lazy val snatcher = new Snatcher()

    private val sum = basis.traits.length
    private lazy val runnersParallel = new concurrent.TrieMap[Runner, Any]
    private lazy val progress = new concurrent.TrieMap[String, Float]
    private lazy val sumParRunning = new AtomicInteger
    private lazy val reporter = new Reporter(feedback, sum)
    @volatile private var remaining: Seq[Trait[_ <: Task]] = _
    @volatile private var normalDone, reinforceDone: Boolean = _
    @volatile private var outFlowTrimmed, prevOutFlow: Out = _

    private[reflow] def start(): Boolean = {
      // 如果当前是子Reflow, 则首先看是不是到了reinforce阶段。
      if (isReinforcing) {
        assert(state.get == COMPLETED || state.forward(COMPLETED))
        state.forward(REINFORCE_PENDING)
        val cache = getCache
        assert(cache.begins.nonNull)
        assert(cache.inputs.nonNull, s"${cache.inputs} 应该缓存有输入参数。")
        remaining = basis.traits
        // 切换到reinforce的开始位置
        breakable {
          while (true) {
            if (remaining.head.isParallel) {
              if (remaining.head.asParallel.traits().forall(t => !cache.begins.contains(t.name$))) {
                remaining = remaining.tail
              } else break
            } else {
              if (remaining.head.name$ != cache.begins.head /*只有一个元素*/ ) {
                remaining = remaining.tail
              } else break
            }
          }
        }
        assert(remaining.nonEmpty)
        prevOutFlow = cache.inputs
        outFlowTrimmed = new Out(cache.outs)
      } else { // 非 reinforce，一开始 remaining == null
        prevOutFlow = new Out(Set.empty[Key$[_]]
        outFlowTrimmed = new Out(basis.inputs)
      }
      if (tryScheduleNext()) {
        Worker.scheduleBuckets()
        true
      } else false
    }

    override private[reflow] def innerError(runner: Runner, e: Exception): Unit = {
      log.e("innerError")(runner.trat.name$)
      // 正常情况下是不会走的，仅用于测试。
      performAbort(runner.trat.name$, runner, forError = true, runner.trat, e)
    }

    override private[reflow] def endRunner(runner: Runner): Unit = {
      log.w("endRunner")(runner.trat.name$)
      runnersParallel -= runner
      if (runnersParallel.isEmpty && state.get$ != ABORTED && state.get$ != FAILED) {
        assert(sumParRunning.get == 0)
        progress.clear()
        if (runner.trat == traitIn) {
          结构待重整
          ， 应该让prevOutFlow与当前合并
          val map = outFlowTrimmed._map.concurrent
          val nulls = outFlowTrimmed._nullValueKeys.concurrent
          doTransform(transIn, map, nulls)

          val flow = new Out(basis.inputs)
          flow.putWith(map, nulls, ignoreDiffType = false, fullVerify = true)

          outFlowNextStage(flow)
          ？ ？ ？
        } else {
          val step = basis.stepOf(runner.trat)
          结构待重整
          ， 应该让prevOutFlow与当前合并
          val map = outFlowTrimmed._map.concurrent
          val nulls = outFlowTrimmed._nullValueKeys.concurrent
          doTransform(basis.transGlobal(remaining.head.name$), map, nulls)
          val trimmed = new Out(basis.outsFlowTrimmed(remaining.head.name$))
          trimmed.putWith(map, nulls, ignoreDiffType = false, fullVerify = true)
          outFlowTrimmed = trimmed

          if (isReinforcing) { // 如果当前任务`是`申请了`reinforce`的，则应该把输出进行合并。
            // TODO: 但也许不应该在这里合并，而是放在onUpdate事件里面。
            val trat = basis.traits(step)
            val cache = getCache
            if (trat.isParallel) {
              if (trat.asParallel.traits().exists(t => cache.begins.contains(t.name$))) {
                outFlowTrimmed 合并
                  cache.outs
              }
            } else {
              assert(cache.begins.length == 1)
              if (cache.begins.head == trat.name$) {
                outFlowTrimmed 合并
                  cache.outs
              }
            }
          } else if (isReinforceRequired) { // 如果当前任务申请了`reinforce`，则应该把输出也缓存起来。
            val trat = basis.traits(step) // 拿到父级trait（注意：如果当前是并行的任务，则runner的trat是子级）。
          val cache = getCache
            if (trat.isParallel) {
              if (trat.asParallel.traits().exists(t => cache.begins.contains(t.name$))) {
                cache.outs = outFlowTrimmed.keysDef()
              }
            } else {
              assert(cache.begins.length == 1)
              if (cache.begins.head == trat.name$) {
                cache.outs = outFlowTrimmed.keysDef()
              }
            }
          }
        }
        if (remaining.isNull) remaining = basis.traits
        else if (remaining.isEmpty) {
        } else remaining = remaining.tail
        if (!tryScheduleNext()) { // 全部运行完毕
          basis.outsFlowTrimmed
          basis.outs
        }
      }
    }

    // 必须在进度反馈完毕之后再下一个, 否则可能由于线程优先级问题, 导致低优先级的进度没有反馈完,
    // 而新进入的高优先级任务又要争用同步锁, 造成死锁的问题。
    private def tryScheduleNext(): Boolean = Locker.sync {
      if (remaining.isNull) { // very beginning...

      } else {
        // 子任务不自行执行`reinforce`阶段。`浏览`阶段完毕就结束了。
        if (isSubReflow && !isReinforcing && state.get.group == COMPLETED.group) return false
        if (remaining.isEmpty) {
          if (reinforceRequired.get()) {
            copy(reinforce /*注意写的时候没有synchronized*/ , remaining)
            outFlowTrimmed = reinforceInput

            state.forward(REINFORCING TODO 看是哪个状态 COMPLETED)
            reinforceMode.set(true)
            reinforce.clear()
          } else return false // 全部运行完毕
        }
      }
      assert(state.get == PENDING /*start()的时候已经PENDING了*/
        || state.forward(PENDING)
        || state.get == REINFORCE_PENDING /*start()的时候已经REINFORCE_PENDING了*/
        || state.forward(REINFORCE_PENDING))

      val trat = if (veryBeginning) traitIn else remaining.head // 不poll(), 以备requireReinforce()的copy().
      if (trat.isParallel) {
        val parallel = trat.asParallel
        val runners = new ListBuffer[Runner]
        parallel.traits().foreach { t =>
          runners += new Runner(Env(t, this))
          // 把并行的任务put进去，不然计算子进度会有问题。
          progress.put(t.name$, 0f)
        }
        runners.foreach(r => runnersParallel += ((r, 0)))
      } else {
        //progress.put(trat.name$, 0f)
        runnersParallel += ((new Runner(Env(trat, this)), 0))
      }
      sumParRunning.set(runnersParallel.size)
      outFlowNextStage(trat)
      // 在调度之前获得结果比较保险
      val hasMore = sumParRunning.get() > 0
      runnersParallel.foreach { kv =>
        val runner = kv._1
        import Period._
        runner.trat.period$ match {
          case INFINITE => Worker.sPreparedBuckets.sInfinite.offer(runner)
          case LONG => Worker.sPreparedBuckets.sLong.offer(runner)
          case SHORT => Worker.sPreparedBuckets.sShort.offer(runner)
          case TRANSIENT => Worker.sPreparedBuckets.sTransient.offer(runner)
        }
      }
      // 不写在这里，原因见下面方法本身：写在这里几乎无效。
      // scheduleBuckets()
      hasMore
    }.get

    private def outFlowNextStage(trat: Trait[_]): Unit = {
      Locker.sync {
        prevOutFlow = outFlowTrimmed
        outFlowTrimmed = new Out(basis.outsFlowTrimmed(trat.name$))
      }
      joinOutFlow(prevOutFlow)
    }

    private def joinOutFlow(flow: Out): Unit = Locker.sync(outFlowTrimmed).get
      .putWith(flow._map, flow._nullValueKeys, ignoreDiffType = true, fullVerify = false)

    private def verifyOutFlow(): Unit = if (debugMode) Locker.sync(outFlowTrimmed).get.verify()

    private def performAbort(name: String, trigger: Runner, forError: Boolean, trat: Trait[_], e: Exception) {
      if (state.forward(if (forError) FAILED else ABORTED)) {
        runnersParallel.foreach { r =>
          val runner = r._1
          runner.abort()
          Monitor.abortion(if (trigger.isNull) null else trigger.trat.name$, runner.trat.name$, forError)
        }
        if (forError) postReport(reporter.reportOnFailed(name /*为null时不会走到这里*/ , e))
        else postReport(reporter.reportOnAbort())
      } else if (state.abort()) {
        // 已经到达COMPLETED/REINFORCE阶段了
      } else {
        // 如果本方法被多次被调用，则会进入本case. 虽然逻辑上并不存在本case, 但没有影响。
        // Throws.abortForwardError();
      }
      interruptSync(true /*既然是中断，应该让reinforce级别的sync请求也终止*/)
    }

    @deprecated(message = "已在{Impl}中实现, 本方法不会被调用。", since = "0.0.1")
    override def sync(): Out = ???

    @throws[InterruptedException]
    override def sync(reinforce: Boolean, milliseconds: Long): Out = {
      val start = System.currentTimeMillis
      Locker.sync(new Locker.CodeC[Out](1) {
        @throws[InterruptedException]
        override protected def exec(cons: Array[Condition]) = {
          // 不去判断mState是因为任务流可能会失败
          while (!(if (reinforce) reinforceDone else normalDone)) {
            if (milliseconds == -1) {
              cons(0).await()
            } else {
              val delta = milliseconds - (System.currentTimeMillis() - start)
              if (delta <= 0 || !cons(0).await(delta, TimeUnit.MILLISECONDS)) {
                throw new InterruptedException()
              }
            }
          }
          outFlowTrimmed
        }
      }, lockSync)
    }.get

    private def interruptSync(reinforce: Boolean) {
      normalDone = true
      if (reinforce) reinforceDone = true
      try {
        Locker.sync(new Locker.CodeC[Unit](1) {
          @throws[InterruptedException]
          override protected def exec(cons: Array[Condition]): Unit = {
            cons(0).signalAll()
          }
        }, lockSync)
      } catch {
        case _: Exception => // 不可能抛异常
      }
    }

    override def abort(): Unit = performAbort(null, null, forError = false, null, null)

    override def getState = state.get

    @deprecated(message = "不要调用。", since = "0.0.1")
    override def isDone = ???

    /**
      * 每个Task都执行。
      */
    override private[reflow] def onTaskStart(trat: Trait[_]): Unit = {
      if (reinforceMode.get) state.forward(REINFORCING)
      else {
        val step = basis.stepOf(trat)
        if (step >= 0) {
          if (state.forward(EXECUTING))
          // 但反馈有且只有一次（上面forward方法只会成功一次）
            if (step == 0) {
              postReport(reporter.reportOnStart())
            } else {
              // progress会在任务开始、进行中及结束时report，这里do nothing.
            }
        }
      }
    }

    override private[reflow] def onTaskProgress(name: String, trat: Trait[_], progress: Float, out: Out, desc: String): Unit = {
      // 因为对于REINFORCING, Task还是会进行反馈，但是这里需要过滤掉。
      if (!reinforceMode.get) {
        val step = basis.stepOf(trat)
        if (step >= 0) { // 过滤掉input任务
          Locker.syncr { // 为了保证并行的不同任务间进度的顺序性，这里还必须得同步。
            val subPogres = subProgress(trat, progress)
            buffer4Reports += (() => reporter.reportOnProgress(name, step, subPogres, out, desc))
          }
          postReport() // 注意就这一个地方写法不同
        }
      }
    }

    // 注意：本方法为单线程操作。
    private def subProgress(trat: Trait[_], pogres: Float): Float = {
      def result: Float = progress.values.sum /*reduce(_ + _)*/ / progress.size

      val value = between(0, pogres, 1)
      // 如果只有一个任务，单线程，不需要同步；
      // 而如果是多个任务，那对于同一个trat，总是递增的（即单线程递增），所以
      // 总体上，即使是并行，那也是发生在不同的trat之间（即不同的trat并行递增），推导出
      // progress.values.sum也是递增的，因此也不需要全局同步。
      if (progress.size <= 1) value
      else {
        if (pogres > 0) {
          assert(progress.contains(trat.name$))
          progress.put(trat.name$, value)
          result
        } else result
      }
    }

    override private[reflow] def onTaskComplete(trat: Trait[_], out: Out, flow: Out): Unit = {
      val step = basis.stepOf(trat)
      if (step < 0) outFlowNextStage(flow)
      else joinOutFlow(flow)
      // 由于不是在这里移除(runnersParallel.remove(runner)), 所以不能用这个判断条件：
      //(runnersParallel.size == 1 && runnersParallel.contains(runner))
      if (sumParRunning.decrementAndGet == 0) {
        不应该在这里
        ， 而应该在 endrunner
          verifyOutFlow()
        Monitor.complete(step, out, flow, outFlowTrimmed)
        if (reinforceMode.get) {
          if (Locker.syncr(remaining.length).get == 1) { // 当前是最后一个
            Monitor.assertStateOverride(state.get, UPDATED, state.forward(UPDATED))
            interruptSync(true)
            // 即使放在interruptSync()的前面，也不能保证事件的到达会在sync()返回结果的前面，因此干脆放在后面算了。
            postReport(reporter.reportOnUpdate(outFlowTrimmed))
          }
        } else if (step == sum - 1) {
          Monitor.assertStateOverride(state.get, COMPLETED, state.forward(COMPLETED))
          interruptSync(!reinforceRequired.get())
          postReport(reporter.reportOnComplete(outFlowTrimmed)) // 注意参数，因为这里是complete.
        } else {
          // 单步任务的完成仅走进度(已反馈，不在这里反馈), 而不反馈完成事件。
        }
      }
    }

    private def postReport(action: => Unit): Unit = {
      Locker.syncr(buffer4Reports += (() => action))
      snatcher.tryOn {
        while (Locker.syncr(buffer4Reports.nonEmpty).get) {
          val f = Locker.syncr(buffer4Reports.remove(0)).get
          f()
        }
      }
    }
  }

  private[reflow] class Runner private(env: Env, trat: Trait[_ <: Task]) extends Worker.Runner(trat: Trait[_ <: Task], null) with Equals {
    def this(env: Env) = this(env, env.trat)

    private implicit lazy val TAG: LogTag = new LogTag(trat.name$)

    private lazy val workDone = new AtomicBoolean(false)
    private lazy val runnerDone = new AtomicBoolean(false)
    @volatile private var aborted = false
    @volatile private var task: Task = _
    private var timeBegin: Long = _

    override def equals(any: scala.Any) = super.equals(any)

    override def canEqual(that: Any) = super.equals(that)

    override def hashCode() = super.hashCode()

    // 该用法遵循 JSR-133
    def abort(): Unit = if (!aborted) {
      aborted = true
      if (task.nonNull) task.abort()
    }

    override def run(): Unit = {
      var working = false
      try {
        task = trat.newTask(env)
        // 判断放在task的创建后面, 配合abort()中的顺序。
        if (aborted) onAbort()
        else {
          onStart()
          working = true
          if (task.exec(this)) {
            working = false
            onWorkDone()
          }
        }
      } catch {
        case e: Exception =>
          log.i("exception:%s", e)
          if (working) {
            e match {
              case _: AbortException => // 框架抛出的, 表示成功中断
                onAbort()
              case e: FailedException =>
                onFailed(e.getCause.as[Exception])
              case e: CodeException => // 客户代码问题
                onException(e)
              case _ =>
                onException(new CodeException(e))
            }
          } else {
            innerError(e)
          }
      } finally {
        runnerDone.set(true)
        endMe()
      }
    }

    private def transOutput(): Out = {
      log.i("222222222222")
      log.i("out: %s", env.out)
      val map = env.out._map.concurrent
      val nulls = env.out._nullValueKeys.concurrent
      log.w("doTransform, prepared:")
      doTransform(env.tracker.basis.transformers(trat.name$), map, nulls)
      log.w("doTransform, done.")
      val dps = env.tracker.basis.dependencies.get(trat.name$)
      log.i("dps: %s", dps.get)
      val flow = new Out(dps.getOrElse(Map.empty[String, Key$[_]]))
      log.i("flow prepared: %s", flow)
      flow.putWith(map, nulls, ignoreDiffType = false, fullVerify = true)
      log.w("flow done: %s", flow)
      flow
    }

    private def afterWork(flow: Out) {
      onComplete(env.out, flow)
      if (aborted) onAbort()
    }

    /** 仅在`成功`执行任务之后才可以调用本方法。 */
    def onWorkDone(): Unit = onWorkEnd(afterWork(transOutput()))

    /** 在执行任务`失败`后应该调用本方法。 */
    def onWorkEnd(doSth: => Unit) {
      import hobby.chenai.nakam.basis.TAG.ThrowMsg
      require(!workDone.getAndSet(true), "如果`task.exec()`返回`true`, `task`不可以再次回调`workDone()。`".tag)
      doSth
      endMe()
    }

    // 这是可靠的，详见 VolatileTest。
    def endMe(): Unit = if (workDone.get && runnerDone.compareAndSet(true, false)) env.tracker.endRunner(this)

    def onStart() {
      log.i("111111111111")
      env.tracker.onTaskStart(trat)
      timeBegin = System.currentTimeMillis
    }

    def onComplete(out: Out, flow: Out) {
      Monitor.duration(trat.name$, timeBegin, System.currentTimeMillis, trat.period$)
      env.tracker.onTaskComplete(trat, out, flow)
    }

    // 人为触发，表示任务失败
    def onFailed(e: Exception, name: String = trat.name$) {
      log.e(e, name)
      withAbort(name, e)
    }

    // 客户代码异常
    def onException(e: CodeException) {
      log.e(e)
      withAbort(trat.name$, e)
    }

    def onAbort(): Unit = env.tracker.performAbort(trat.name$, this, forError = false, trat, null)

    def withAbort(name: String, e: Exception) {
      if (!aborted) aborted = true
      env.tracker.performAbort(name, Runner.this, forError = true, trat, e)
    }

    def innerError(e: Exception) {
      log.e(e)
      env.tracker.innerError(this, e)
      throw new InnerError(e)
    }
  }

  private[reflow] class SubReflowTask(env: Env) extends Task(env: Env) {
    @volatile private var scheduler: Scheduler = _

    override private[reflow] def exec$(runner: Runner) = {
      progress(0)
      val trat = env.trat.as[ReflowTrait]
      scheduler = trat.reflow.start(In.from(env.input), trat.feedback.withPoster(trat.poster).join(env, runner, progress(1)), null, env)
      false // 异步。
    }

    override protected def doWork(): Unit = {}

    override protected def onAbort(): Unit = {
      if (scheduler.nonNull) scheduler.abort()
      super.onAbort()
    }
  }

  private[reflow] class SubReflowFeedback(env: Env, runner: Runner, doSth: () => Unit) extends Feedback {
    override def onStart(): Unit = runner.onStart()

    override def onProgress(name: String, out: Out, count: Int, sum: Int, sub: Float, desc: String): Unit =
      env.tracker.onTaskProgress(name, env.trat, (count + sub) / sum, out, desc)

    override def onComplete(out: Out): Unit = {
      doSth()
      if (out ne env.out) env.out.fillWith(out)
      runner.onWorkDone()
    }

    override def onUpdate(out: Out): Unit = onComplete(out)

    override def onAbort(): Unit = {
      runner.onAbort()
      runner.onWorkEnd()
    }

    override def onFailed(name: String, e: Exception): Unit = {
      runner.onFailed(e, name)
      runner.onWorkEnd()
    }
  }

  private[reflow] implicit class FeedbackJoin(fb: Feedback = null) {
    def join(env: Env, runner: Runner, doSth: => Unit): Feedback = {
      val feedback = new Feedback.Observable
      feedback.addObservers(new SubReflowFeedback(env, runner, () => doSth))
      if (fb.nonNull) feedback.addObservers(fb)
      feedback
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////
  //************************************ Reporter ************************************//

  /**
    * 该结构的目标是保证进度反馈的递增性。同时保留关键点，丢弃密集冗余。
    * 注意：事件到达本类，已经是单线程操作了。
    */
  private class Reporter(feedback: Feedback, sum: Int) {
    private var _step: Int = _
    private var _subProgress: Float = _
    private var _stateResetted = true

    private[Tracker] def reportOnStart(): Unit = {
      assert(_stateResetted)
      _stateResetted = false
      eatExceptions(feedback.onStart())
    }

    private[Tracker] def reportOnProgress(name: String, step: Int, subProgress: Float, out: Out, desc: String): Unit = {
      assert(subProgress >= _subProgress, s"调用没有同步？`$name`:`$desc`")
      if (_stateResetted) {
        assert(step == _step + 1)
        _step = step
        _subProgress = 0
      } else assert(step == _step)
      if (subProgress > _subProgress) {
        _subProgress = subProgress
        // 一定会有1的, Task#exec()里有progress(1), 会使单/并行任务到达1.
        if (subProgress == 1) {
          _stateResetted = true
        }
      }
      eatExceptions(feedback.onProgress(name, out, step, sum, subProgress, desc))
    }

    private[Tracker] def reportOnComplete(out: Out): Unit = {
      assert(_step == sum - 1 && _subProgress == 1)
      eatExceptions(feedback.onComplete(out))
    }

    private[Tracker] def reportOnUpdate(out: Out): Unit = eatExceptions(feedback.onUpdate(out))

    private[Tracker] def reportOnAbort(): Unit = eatExceptions(feedback.onAbort())

    private[Tracker] def reportOnFailed(name: String, e: Exception): Unit = eatExceptions(feedback.onFailed(name, e))
  }
}