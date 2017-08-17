/* =========================================================================================
 * Copyright © 2013-2017 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

import scala.concurrent.duration._

name := "netty-with-kamon"
scalaVersion := "2.12.3"

enablePlugins(JavaAgent)

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:postfixOps",
  "-unchecked",
  "-Xlint",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard",
  "-Xfuture")

fork in run := true

resolvers += Resolver.bintrayRepo("kamon-io", "releases")
resolvers += Resolver.bintrayRepo("kamon-io", "snapshots")

libraryDependencies ++= Seq(
  "io.kamon"        %% "kamon-core"                     % "1.0.0-RC1-61029e115272b9af3f4460b311d3a2e650c806e3",
  "io.kamon"        %% "agent-scala-extension"          % "0.0.3-experimental",
  "io.netty"        %  "netty-all"                      % "4.0.50.Final",
  "io.netty"        %  "netty-transport-native-epoll"   % "4.0.50.Final"    classifier "linux-x86_64",
  "ch.qos.logback"  %  "logback-classic"                % "1.0.13",
  "io.kamon"        %% "kamon-testkit"                  % "1.0.0-RC1-61029e115272b9af3f4460b311d3a2e650c806e3" % "test"
)

javaAgents += "org.aspectj" % "aspectjweaver"  % "1.8.10"  % "compile;test;runtime"

mainClass := Some("playground.server.ServerStart")
