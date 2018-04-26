name := baseDirectory.value.getName

organization := "hobby.wei.c"

version := "0.0.1-SNAPSHOT"

scalaVersion := "2.11.7"

javacOptions ++= Seq("-source", "1.7", "-target", "1.7")

// 解决生成文档报错导致 jitpack.io 出错的问题。
publishArtifact in packageDoc := false

// TODO: 独立使用本库的话，应该启用下面的设置。
//lazy val scalaSettings = Seq(
//  scalaVersion := "2.11.7"
//)
//
//lazy val root = Project(id = "reflow", base = file("."))
//  .dependsOn(/*lang*/)
//  .settings(scalaSettings,
//    aggregate in update := false
//  )

exportJars := true

offline := true

// 如果要用 jitpack 打包的话就加上，打完了再注掉。
//resolvers += "jitpack" at "https://jitpack.io"

libraryDependencies ++= Seq(
  // 如果要用 jitpack 打包的话就加上，打完了再注掉。
  // TODO: 独立使用本库的话，应该启用本依赖。
//  "com.github.dedge-space" % "annoguard" % "1.0.3-beta",
//  "com.github.dedge-space" % "scala-lang" % "fa67081527",

  // ScalaTest 的标准引用。
  "junit" % "junit" % "[4.12,)" % "test",
  "org.scalatest" %% "scalatest" % "[2.11,)" % "test"
)
