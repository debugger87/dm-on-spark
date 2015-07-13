import AssemblyKeys._

organization := "com.debugger87.dm"

name := "dm-on-spark"

version := "1.0"

scalaVersion := "2.10.4"

resolvers ++= Seq(
  "maven.mei.fm" at "http://maven.mei.fm/nexus/content/groups/public/"
)

assemblySettings

mergeStrategy in assembly <<= (mergeStrategy in assembly) { (old) => {
  case m if m.toLowerCase.matches("meta-inf.*\\.mf$") => MergeStrategy.discard
  case m if m.toLowerCase.matches("meta-inf.*\\.sf$") => MergeStrategy.discard
  case m if m.toLowerCase.matches("meta-inf.*\\.rsa$") => MergeStrategy.discard
  case m if m.toLowerCase.matches("meta-inf.*\\.dsa$") => MergeStrategy.discard
  case "reference.conf" => MergeStrategy.concat
  case _ => MergeStrategy.first
}
}

net.virtualvoid.sbt.graph.Plugin.graphSettings