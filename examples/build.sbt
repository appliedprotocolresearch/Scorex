name := "scorex-examples"

libraryDependencies ++= Seq(
  "org.scalactic" %% "scalactic" % "3.0.1" % "test",
  "org.scalatest" %% "scalatest" % "3.0.1" % "test",
  "org.scalacheck" %% "scalacheck" % "1.13.+" % "test",
  "org.scorexfoundation" %% "iodb" % "0.3.2",
  "com.typesafe.akka" %% "akka-testkit" % "2.4.17" % "test"
)

mainClass in assembly := Some("examples.prism1.PrismV1App")

assemblyJarName in assembly := "twinsChain.jar"

parallelExecution in Test := true

testForkedParallel in Test := true

test in assembly := {}

coverageExcludedPackages := "examples\\.prism1\\.api\\.http.*"
