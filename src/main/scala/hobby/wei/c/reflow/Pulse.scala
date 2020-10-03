/*
 * Copyright (C) 2018-present, Chenai Nakam(chenai.nakam@gmail.com)
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

import hobby.chenai.nakam.basis.TAG
import hobby.chenai.nakam.basis.TAG.{LogTag, ThrowMsg}
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.chenai.nakam.lang.TypeBring.AsIs
import hobby.chenai.nakam.tool.pool.S._2S
import hobby.wei.c.reflow.Assist.eatExceptions
import hobby.wei.c.reflow.Feedback.Progress
import hobby.wei.c.reflow.Feedback.Progress.Policy
import hobby.wei.c.reflow.Pulse.Reporter
import hobby.wei.c.reflow.Reflow.{debugMode, logger => log}
import hobby.wei.c.reflow.State._
import hobby.wei.c.tool
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap

/**
  * 脉冲步进流式数据处理器。
  * <p>
  * 数据流经`大规模集成任务集（Reflow）`，能够始终保持输入时的先后顺序，会排队进入各个任务，每个任务可保留前一个数据在处理时
  * 特意留下的标记。无论在任何深度的子任务中，也无论前一个数据在某子任务中停留的时间是否远大于后一个。
  *
  * @param reflow        每个`流处理器`都`Base`在一个主`Reflow`上。
  * @param abortIfError  当有一次输入出现错误时，是否中断。默认为`false`。
  * @param inputCapacity 输入数据的缓冲容量。
  *
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 01/07/2018;
  *          1.5, 04/10/2019, fix 了一个很重要的 bug（版本号与`Tracker`保持一致：`Tracker`也作了修改）;
  *          1.7, 29/09/2020, 增加了`strategy`和`queueCapacity`实现，至此算是完美了；
  *          1.8, 01/10/2020, 修复`Tactic.snatcher`处于不同上下文导致线程不串行而偶现异常的问题。
  */
class Pulse(val reflow: Reflow, feedback: Pulse.Feedback, abortIfError: Boolean = false,
            inputCapacity: Int = Config.DEF.maxPoolSize * 3)(implicit strategy: Policy, poster: Poster)
  extends Scheduler with TAG.ClassName {
  private lazy val snatcher4Input = new tool.Snatcher
  private lazy val snatcher = new tool.Snatcher.ActionQueue()
  private lazy val reporter = new Reporter(feedback.wizh(poster))
  private lazy val state = new Scheduler.State$()
  private lazy val serialNum = new AtomicLong(0)
  private lazy val inputQueue = new LinkedBlockingQueue[In](inputCapacity)
  @volatile private var head: Option[Tactic] = None
  @volatile private var maxStartedSNum: Long = -1
  @volatile private var maxCompletedSNum: Long = -1

  /**
    * 流式数据输入。应使用单线程输入，如果要用多线程，应自行保证线程安全。
    * 注意：输入数据`in`会首先存入[[LinkedBlockingQueue]]，如果达到了[[inputCapacity]] size, 则会阻塞。
    */
  @throws[InterruptedException]
  def input(in: In): Unit = if (!isDone) {
    inputQueue.put(in)
    consumeInputQueue(maxStartedSNum)
  }

  private def consumeInputQueue(onStartedSNum: Long): Unit = {
    // 注意：不要把这行包裹进`tryOn`，详见实现。
    this.synchronized { // 其实这里不需要串行
      if (onStartedSNum > maxStartedSNum) maxStartedSNum = onStartedSNum
    }
    snatcher4Input.tryOn {
      if (debugMode) log.i("[consumeInputQueue]maxStartedSNum:%d.", maxStartedSNum)
      while (!isDone && maxStartedSNum + 3 >= serialNum.get && !inputQueue.isEmpty) {
        if (debugMode) log.i("[consumeInputQueue]serialNum:%d, maxStartedSNum: %d.", serialNum.get, maxStartedSNum)
        state.forward(PENDING)
        // 无论我是不是第一个，原子交换。
        head = Some(new Tactic(head, this, serialNum.getAndIncrement, strategy, { sn =>
          consumeInputQueue(sn)
        }, { sn =>
          this.synchronized {
            if (sn > maxCompletedSNum) maxCompletedSNum = sn
          }
        }))
        head.get.start(inputQueue.take())
      }
    }
  }

  def currentScheduledSize = serialNum.get
  def currentCompletedSize = maxCompletedSNum + 1
  def inputQueueSize = inputQueue.size()

  /** 当前所有的输入是否都已成功运行。瞬时状态，即使当前返回`true`了，可能后续会再次输入，就又返回`false`了。*/
  def isCurrAllCompleted = inputQueue.isEmpty && currentScheduledSize == currentCompletedSize

  /**
    * 是否已经结束运行。
    *
    * @return 若为`true`，则新的[[input]]会被忽略，已经在缓冲队列中的[[In]]也将不被执行。
    */
  override def isDone = {
    val s = getState
    s == COMPLETED /*不应该存在*/ || s == FAILED || s == ABORTED || s == UPDATED /*不应该存在*/
  }

  override def getState = state.get

  @deprecated
  override def sync(reinforce: Boolean = false) = ??? // head.get.scheduler.sync(reinforce)
  @deprecated
  override def sync(reinforce: Boolean, milliseconds: Long) = ??? // head.get.scheduler.sync(reinforce, milliseconds)

  override def abort(): Unit = snatcher.queAc {
    if (state.forward(PENDING)) { // 说明还没有开始
      if (state.forward(ABORTED)) {
        // reporter.reportOnAbort(serialNum.get, None)
      }
    }
    abortHead(head)
    head = None
  }

  private[reflow] def onFailed(trat: Trait, e: Exception): Unit = snatcher.queAc {
    if (abortIfError) {
      if (state.forward(FAILED)) {
        // reporter.reportOnFailed(serialNum.get, trat, e)
      }
      // 后面的`state.forward()`是不起作用的。
      abort()
    }
  }

  private[reflow] def onAbort(trigger: Option[Trait]): Unit = snatcher.queAc {
    if (state.forward(ABORTED)) {
      // reporter.reportOnAbort(serialNum.get, trigger)
    }
    abort()
  }

  private[reflow] def abortHead(head: Option[Tactic]): Unit = head.foreach { tac =>
    // 顺序很重要，由于`abort()`事件会触发tac重置 head, 所以这里先引用，后执行，确保万无一失。
    val thd = tac.head
    while (tac.scheduler.isNull) {
      // 稍等片刻，等`scheduler`拿到实例。
      Thread.`yield`()
    }
    tac.scheduler.abort()
    abortHead(thd) // 尾递归
  }

  private[reflow] class Tactic(@volatile var head: Option[Tactic], pulse: Pulse, serialNum: Long, strategy: Policy,
                               onStartCallback: Long => Unit, onCompleteCallback: Long => Unit) extends TAG.ClassName {
    private lazy val snatcher = new tool.Snatcher
    private lazy val roadmap = new TrieMap[(Int, String), Out]
    private lazy val suspend = new TrieMap[(Int, String), () => Unit]
    // @volatile private var follower: Tactic = _
    // 不可以释放实例，只可以赋值。
    @volatile var scheduler: Scheduler = _

    // `in`参数放在这里以缩小作用域。
    def start(in: In): Unit = {
      head.foreach(follow)
      scheduler = pulse.reflow.start(in, feedback, strategy,
        /*不使用外部客户代码提供的`poster`，以确保`feedback`和`cacheBack`反馈顺序问题。*/
        null, null, interact)
    }

    private def follow(tac: Tactic): Unit = {
      // require(tac.follower.isNull)
      // tac.follower = this
    }

    // 应该放到`object`里，相当于`static`的。
    private def doPushForward(snatcher: tool.Snatcher, map: TrieMap[(Int, String), () => Unit], data: TrieMap[(Int, String), Out],
                              serialNum: Long): Unit = snatcher.tryOn {
      // fix bug: 1.8, 01/10/2020. 加了`snatcher`变量，否则由于每个`interact`回调本方法都是在两个不同的`Tactic`实例中，不同
      // `snatcher`无法串行化以下代码块。
      lazy val keys = data.keys
      for ((k, go) <- map) { // 放到`map`中的肯定是并行的任务或单个任务，因此遍历时不用管它们放进去的顺序。
        // bug fix: 1.5, 04/10/2019.
        // 切记不能用`contains`，不然如果`value eq null`，则`contains`返回`false`，即使`keySet`也一样。
        // 前一版的 bug：
        // 1. 主要是这个原因导致的；
        // 2. 还有一个严重影响性能的问题：如果嵌套了子层`reflow`，则必须等该层`reflow`的所有任务执行完毕才能`endRunner()`,进而
        // 调用`interact.evolve()`，而后才能推动下一个数据的该层`reflow`继续。bug fix 已对`ReflowTrait`作了忽略处理。
        if (keys.exists(_ == k)) {
          if (debugMode) log.i("(%d)[doPushForward](%s, %s).", serialNum, k._1, k._2.s)
          map.remove(k)
          go()
        }
      }
    }

    private lazy val interact = new Pulse.Interact {
      override def evolve(depth: Int, trat: Trait, cache: Out): Unit = {
        if (debugMode) log.i("(%d)[interact.evolve](%s, %s).", serialNum /* + 1*/ , depth, trat.name$.s)
        roadmap.put((depth, trat.name$), cache)
        doPushForward(snatcher, suspend, roadmap, serialNum + 1)
      }

      override def forward(depth: Int, trat: Trait, go: () => Unit): Unit = {
        if (debugMode) log.i("(%d)[interact.forward](%s, %s).", serialNum, depth, trat.name$.s)
        head.fold(go()) { tac =>
          tac.suspend.put((depth, trat.name$), go)
          doPushForward(tac.snatcher, tac.suspend, tac.roadmap, serialNum)
        }
      }

      override def getCache(depth: Int, trat: Trait): Out = {
        if (debugMode) log.i("(%d)[interact.getCache](%s, %s).", serialNum, depth, trat.name$.s)
        head.fold[Out](null) {
          _.roadmap.remove((depth, trat.name$)).orNull
        }
      }
    }

    // 再次用`pulse.snatcher`串行化没意义。
    // 1. `feedback`本身已经被串行化了；
    // 2. 影响性能，同时也是个伪命题（没有绝对的先后顺序，无法判定）；
    // 3. 性能优先。并行程序应该最大化性能。
    private lazy val feedback = new Feedback {
      override def onPending(): Unit = pulse.reporter.reportOnPending(serialNum)

      override def onStart(): Unit = {
        onStartCallback(serialNum)
        pulse.reporter.reportOnStart(serialNum)
      }

      override def onProgress(progress: Progress, out: Out, depth: Int): Unit =
        pulse.reporter.reportOnProgress(serialNum, progress, out, depth)

      override def onComplete(out: Out): Unit = {
        onCompleteCallback(serialNum)
        pulse.reporter.reportOnComplete(serialNum, out)
        // 释放`head`。由于总是会引用前一个，会造成内存泄露。
        head = None
        // 本次脉冲走完所有`Task`，结束。
      }

      override def onUpdate(out: Out): Unit = {
        assert(assertion = false, "对于`Pulse`中的`Reflow`，不应该走到`onUpdate`这里。".tag)
      }

      override def onAbort(trigger: Option[Trait]): Unit = {
        pulse.reporter.reportOnAbort(serialNum, trigger)
        pulse.onAbort(trigger)
        // 放到最后
        head = None
      }

      override def onFailed(trat: Trait, e: Exception): Unit = {
        pulse.reporter.reportOnFailed(serialNum, trat, e)
        pulse.onFailed(trat, e)
        // 放到最后
        head = None
      }
    }
  }
}

object Pulse {
  trait Feedback extends Equals {
    def onPending(serialNum: Long): Unit
    def onStart(serialNum: Long): Unit
    def onProgress(serialNum: Long, progress: Progress, out: Out, depth: Int): Unit
    def onComplete(serialNum: Long, out: Out): Unit
    def onAbort(serialNum: Long, trigger: Option[Trait]): Unit
    def onFailed(serialNum: Long, trat: Trait, e: Exception): Unit

    override def equals(any: Any) = super.equals(any)
    override def canEqual(that: Any) = false
  }

  object Feedback {
    trait Adapter extends Feedback {
      override def onPending(serialNum: Long): Unit = {}
      override def onStart(serialNum: Long): Unit = {}
      override def onProgress(serialNum: Long, progress: Progress, out: Out, depth: Int): Unit = {}
      override def onComplete(serialNum: Long, out: Out): Unit = {}
      override def onAbort(serialNum: Long, trigger: Option[Trait]): Unit = {}
      override def onFailed(serialNum: Long, trat: Trait, e: Exception): Unit = {}
    }

    abstract class Butt[T >: Null <: AnyRef](kce: Kce[T], watchProgressDepth: Int = 0) extends Adapter {
      override def onProgress(serialNum: Long, progress: Progress, out: Out, fromDepth: Int): Unit = {
        super.onProgress(serialNum, progress, out, fromDepth)
        if (fromDepth == watchProgressDepth && out.keysDef().contains(kce))
          onValueGotOnProgress(serialNum, out.get(kce), progress)
      }

      override def onComplete(serialNum: Long, out: Out): Unit = {
        super.onComplete(serialNum, out)
        onValueGotOnComplete(serialNum, out.get(kce))
      }

      def onValueGotOnProgress(serialNum: Long, value: Option[T], progress: Progress): Unit = {}
      def onValueGotOnComplete(serialNum: Long, value: Option[T]): Unit
    }

    abstract class Lite[-T <: AnyRef](watchProgressDepth: Int = 0) extends Butt(lite.Task.defKeyVType, watchProgressDepth) {
      @deprecated
      override final def onValueGotOnProgress(serialNum: Long, value: Option[AnyRef], progress: Progress): Unit =
        liteValueGotOnProgress(serialNum, value.as[Option[T]], progress)

      @deprecated
      override final def onValueGotOnComplete(serialNum: Long, value: Option[AnyRef]): Unit =
        liteOnComplete(serialNum, value.as[Option[T]])

      def liteValueGotOnProgress(serialNum: Long, value: Option[T], progress: Progress): Unit = {}
      def liteOnComplete(serialNum: Long, value: Option[T]): Unit
    }

  }

  private[reflow] trait Interact {
    /**
      * 进展，完成某个小`Task`。
      * <p>
      * 表示上一个`Tactic`反馈的最新进展（或当前`Tactic`要反馈给下一个`Tactic`的进展）。
      *
      * @param depth `SubReflow`的嵌套深度，顶层为`0`。在同一深度下的`trat`名称不会重复。
      * @param trat  完成的`Task`。
      * @param cache 留给下一个路过`Task`的数据。
      */
    def evolve(depth: Int, trat: Trait, cache: Out): Unit

    /**
      * 当前`Tactic`的某[[Task]]询问是否可以启动执行。必须在前一条数据输入[[Pulse.input]]执行完毕该`Task`后才可以启动。
      *
      * @param depth 同上。
      * @param trat  同上。
      * @param go    一个函数，调用以推进询问的`Task`启动执行。如果在询问时，前一个`Tactic`的该`Task`未执行完毕，则应该
      *              将本参数缓存起来，以备在`evolve()`调用满足条件时，再执行本函数以推进`Task`启动。
      */
    def forward(depth: Int, trat: Trait, go: () => Unit)

    /**
      * 当前`Tactic`的某`Task`执行的时候，需要获得上一个`Tactic`留下的数据。
      *
      * @param depth 同上。
      * @param trat  同上。
      * @return
      */
    def getCache(depth: Int, trat: Trait): Out
  }

  implicit class WithPoster(feedback: Feedback) {
    def wizh(poster: Poster): Feedback = if (poster.isNull) feedback else if (feedback.isNull) feedback else new Feedback {
      require(poster.nonNull)
      override def onPending(serialNum: Long): Unit = poster.post(feedback.onPending(serialNum))
      override def onStart(serialNum: Long): Unit = poster.post(feedback.onStart(serialNum))
      override def onProgress(serialNum: Long, progress: Progress, out: Out, depth: Int): Unit = poster.post(feedback.onProgress(serialNum, progress, out, depth))
      override def onComplete(serialNum: Long, out: Out): Unit = poster.post(feedback.onComplete(serialNum, out))
      override def onAbort(serialNum: Long, trigger: Option[Trait]): Unit = poster.post(feedback.onAbort(serialNum, trigger))
      override def onFailed(serialNum: Long, trat: Trait, e: Exception): Unit = poster.post(feedback.onFailed(serialNum, trat, e))
    }
  }

  private[reflow] class Reporter(feedback: Feedback)(implicit tag: LogTag) {
    private[reflow] def reportOnPending(serialNum: Long): Unit = eatExceptions(feedback.onPending(serialNum))
    private[reflow] def reportOnStart(serialNum: Long): Unit = eatExceptions(feedback.onStart(serialNum))
    private[reflow] def reportOnProgress(serialNum: Long, progress: Progress, out: Out, depth: Int): Unit = eatExceptions(feedback.onProgress(serialNum, progress, out, depth))
    private[reflow] def reportOnComplete(serialNum: Long, out: Out): Unit = eatExceptions(feedback.onComplete(serialNum, out))
    private[reflow] def reportOnAbort(serialNum: Long, trigger: Option[Trait]): Unit = eatExceptions(feedback.onAbort(serialNum, trigger))
    private[reflow] def reportOnFailed(serialNum: Long, trat: Trait, e: Exception): Unit = eatExceptions(feedback.onFailed(serialNum, trat, e))
  }
}
