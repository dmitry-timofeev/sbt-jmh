package pl.project13.scala.sbt

import sbt._
import sbt.Keys._
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.generators.bytecode.JmhBytecodeGenerator
import sbt.CommandStrings._
import java.io.PrintWriter

import scala.tools.nsc.util.ScalaClassLoader.URLClassLoader

object SbtJmh extends Plugin {

  import JmhKeys._

  lazy val jmhSettings = Seq(
    sourceGenerators in Jmh := (sourceGenerators in Compile).value,
    sourceGenerators in Jmh <+= generateJavaSources in Jmh,

    mainClass in (Compile, run) := Some("org.openjdk.jmh.Main"),

    fork in (Compile, run) := true, // makes sure that sbt manages classpath for JMH when forking

    sourceDirectories in Compile += target.value / s"scala-${scalaBinaryVersion.value}" / "generated-sources" / "jmh",

    generatorType in Jmh := "default",

    generateJavaSources in Jmh := generateBenchmarkJavaSources(
      streams.value,
      (outputTarget in Jmh).value,
      (generatorType in Jmh).value,
      (dependencyClasspath in Compile).value,
      scalaBinaryVersion.value
    ),

    outputTarget in Jmh := crossTarget.value,

    compile in Jmh := {
      streams.value.log.info("Compiling generated JMH benchmarks...")
      val generatedJava = (generateJavaSources in Jmh).value
      myCompile(streams.value, (compileInputs in (Compile, compile)).value, generatedJava)
    },

    version in Jmh := "1.0",

    // includes the asm jar only if needed
    libraryDependencies ++= {
      val jmhVersion = (version in Jmh).value
      Seq(
        "org.openjdk.jmh" % "jmh-core"                 % jmhVersion,   // GPLv2
        "org.openjdk.jmh" % "jmh-generator-bytecode"   % jmhVersion,   // GPLv2
        "org.openjdk.jmh" % "jmh-generator-reflection" % jmhVersion    // GPLv2
      ) ++ ((generatorType in Jmh).value match {
        case "default" | "reflection" => Nil // default == reflection (0.9)
        case "asm"                    => Seq("org.openjdk.jmh" % "jmh-generator-asm" % jmhVersion)    // GPLv2
        case unknown                  => throw new IllegalArgumentException(s"Unknown benchmark generator type: $unknown, please use one of the supported generators!")
      })
    },

    compile in Jmh <<= (compile in Jmh).dependsOn(generateJavaSources in Jmh, compile in Compile),
    compile in Jmh <<= (compile in Jmh).dependsOn(),
    compile in Jmh <<= (compile in Jmh).dependsOn(compile in Compile),

    run in Jmh <<= (run in Compile).dependsOn(compile in Jmh),
    run in Compile <<= (run in Compile).dependsOn(compile in Jmh)
  )

  def generateBenchmarkJavaSources(s: TaskStreams, outputTarget: File, generatorType: String, classpath: Seq[Attributed[File]], scalaBinaryV: String): Seq[File] = {
    require(outputTarget ne null, "outputTarget in Jmh must not be null!")
    s.log.info(s"Generating JMH benchmark Java source files...")

    val compiledBytecodeDirectory = outputTarget / "classes"
    val outputSourceDirectory = outputTarget / "generated-sources" / "jmh"
    val outputResourceDirectory = compiledBytecodeDirectory

    // since we might end up using reflection (even in ASM generated benchmarks), we have to set up the classpath to include classes our code depends on
    val bench = classOf[Benchmark]
    val loader = new URLClassLoader(classpath.map(_.data.toURI.toURL), bench.getClassLoader)
    Thread.currentThread().setContextClassLoader(loader)

    JmhBytecodeGenerator.main(Array(compiledBytecodeDirectory, outputSourceDirectory, outputResourceDirectory, generatorType).map(_.toString))
    (outputSourceDirectory ** "*").filter(_.isFile).get
  }

  /** Compiler run, with additional files to compile (JMH generated sources) */
  def myCompile(s: TaskStreams, ci: Compiler.Inputs, javaToCompile: Seq[File]): inc.Analysis = {
    lazy val x = s.text(ExportStream)
    def onArgs(cs: Compiler.Compilers) = {
      cs.copy(
        scalac = cs.scalac.onArgs(exported(x, "scalac")),
        javac = cs.javac.onArgs(exported(x, "javac")))
    }
    val i = ci
      .copy(compilers = onArgs(ci.compilers))
      .copy(config = ci.config.copy(sources = ci.config.sources ++ javaToCompile))
    try Compiler(i, s.log) finally x.close() // workaround for #937
  }

  def exported(w: PrintWriter, command: String): Seq[String] => Unit = args =>
    w.println((command +: args).mkString(" "))


  object JmhKeys {
    val Jmh = config("jmh") extend Compile

    val generateJavaSources = taskKey[Seq[File]]("Generate benchmark JMH Java code")

    val outputTarget = settingKey[File]("Directory where the bytecode to be consumed and generated sources should be written to (`target` or sometimes `target/scala-2.10`)")

    // issue: JMH 0.9 has an 1-off error in the args parser, which makes it ignore this option - this is reported and will be fixed soon
    // see also: https://twitter.com/ktosopl/status/480452428396257283
    val generatorType = settingKey[String]("WARNING: Does not work with JMH 0.9 because of small error in args parser; Benchmark code generator type. Available: `default`, `reflection` or `asm`.")

    val generateInstrumentedClasses = taskKey[Seq[File]]("Generate instrumented JMH code")

  }

}
