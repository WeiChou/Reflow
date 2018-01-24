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

import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.wei.c.reflow.Assist._
import hobby.wei.c.tool.Locker

import scala.collection._
import scala.ref.WeakReference

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 14/08/2016
  */
abstract class In protected(_keys: Set[Key$[_]]) {
  private[reflow] val keys: immutable.Set[Key$[_]] = requireKey$kDiff(requireElemNonNull(_keys)).to[immutable.Set]
  private[reflow] var trans: immutable.Set[Transformer[_, _]] = _

  def transition(trans: Transformer[_, _]): In = transition(Set(trans))

  def transition(tranSet: Set[Transformer[_, _]]): In = {
    trans = requireTransInTypeSame(requireElemNonNull(tranSet)).to[immutable.Set]
    this
  }

  private[reflow] def fillValues(out: Out) {
    out.keysDef().intersect(keys).foreach(key =>
      out.put(key.key, loadValue(key.key).get)
    )
  }

  protected def loadValue(key: String): Option[Any]
}

object In {
  def map(key: String, value: Any): In = map(Map((key, value)))

  def map(map: Map[String, Any]): In = new M(generate(map), map)

  def add(key: String, value: Any): Builder = new Builder().add(key, value)

  class Builder private() {
    private val map = new mutable.AnyRefMap[String, Any]
    private var tb: Helper.Transformers.Builder = _

    def add(key: String, value: Any): Builder = {
      map.put(key, value)
      this
    }

    def add(trans: Transformer[_, _]): Builder = {
      if (tb == null) tb = Helper.Transformers.add(trans)
      else tb.add(trans)
      this
    }

    def ok(): In = In.map(map).transition(if (tb.isNull) null else tb.ok())
  }

  private def generate(map: Map[String, Any]): Set[Key$[_]] = {
    val set = new mutable.HashSet[Key$[_]]
    map.foreach { kv: (String, Any) => set.add(new Key$(kv._1, kv._2.getClass) {}) }
    set
  }

  private class M private(keys: Set[Key$[_]], map: Map[String, Any]) extends In(keys: Set[Key$[_]]) {
    override protected def loadValue(key: String) = map.get(key)
  }

  def empty(): In = Locker.lazyGetr(getRef(emptyRef).get) {
    val in = new In(Helper.Keys.empty()) {
      override private[reflow] def fillValues(out: Out): Unit = {}

      override protected def loadValue(key: String) = null
    }
    emptyRef = new WeakReference(in)
    in
  }(Locker.getLockr(this)).get

  private var emptyRef: WeakReference[In] = _
}
