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

scalaVersion := "2.12.8"
val akkaVersion = "2.5.21"
val akkaHTTPVersion = "10.1.7"
val akka = "com.typesafe.akka"

libraryDependencies ++= Seq(
  akka %% "akka-actor" % akkaVersion,
  akka %% "akka-stream" % akkaVersion,
  akka %% "akka-http-core" % akkaHTTPVersion,
  akka %% "akka-http" % akkaHTTPVersion,
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
  akka % "akka-slf4j_2.12" % "2.4.12",
  "net.debasishg" %% "redisclient" % "3.8",
  "ch.qos.logback" % "logback-classic" % "1.1.3" % Runtime,
  "io.spray" %%  "spray-json" % "1.3.4",
  "org.json4s" %% "json4s-jackson" % "3.2.11",
  "org.json4s" %% "json4s-ext" % "3.2.11"
)