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

import hobby.wei.c.anno.proguard.{KeepMp$, KeepVp$}

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 22/07/2016
  */
@KeepVp$
@KeepMp$ // 保留枚举名称
object State extends Enumeration {
  type Tpe = State

  private[reflow] case class State(group: Int, sn: Int) extends Val {
    def canOverrideWith(state: Tpe): Boolean = (
      state.group == group && Math.abs(state.sn - sn) == 1 /*自己不能覆盖自己*/
        || state.group == group + 1
        || state.group > group + 1 && state.sn == sn
      )
  }

  val IDLE = State(0, 0)
  /**
    * 队列提交之后或任务之间的等待（调度或其它高优先级任务）间隙。
    */
  val PENDING = State(1, 10)
  val EXECUTING = State(1, 11)
  /**
    * 第一阶段完成, 不代表结束。
    * 要查询是否结束, 请使用{@link Scheduler#isDone()}.
    */
  val COMPLETED = State(2, 100)
  val FAILED = State(2, 200)
  val ABORTED = State(2, 300)
  /**
    * [强化]运行任务的等待间隙。同{@link #PENDING}
    */
  val REINFORCE_PENDING = State(5, 100 /*只能override COMPLETED*/)
  /**
    * [强化]运行状态。每个任务流都可以选择先[摘要]再[强化]运行。
    * <p>
    * 摘要: 第一遍快速给出结果。可类比为阅读的[浏览]过程;
    * 强化: 第二遍精细严格的运行。可类比为阅读的精读。
    * <p>
    * 摘要过程, 会执行完整的任务流;
    * 强化过程, 仅从第一个请求强化运行的任务开始直到完成整个任务流。第一个任务不会重新输入参数。
    */
  val REINFORCING = State(5, 101)
  /**
    * 完成了REINFORCING.
    */
  val UPDATED = State(6 /*只能override REINFORCING*/ , 1000)
}
