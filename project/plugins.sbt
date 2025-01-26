addSbtPlugin("org.typelevel" % "sbt-tpolecat" % "0.5.2")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.7")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.3.0")
addSbtPlugin("com.geirsson" % "sbt-ci-release" % "1.5.7")
addSbtPlugin("com.github.sbt" % "sbt-github-actions" % "0.24.0")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.6.2") // override mdoc version from microsite
addSbtPlugin("com.eed3si9n" % "sbt-projectmatrix" % "0.10.1")
// addSbtPlugin("com.47deg" % "sbt-microsites" % "1.4.4")

// There are conflicts with scala-xml 1.0 vs 2.0 with microsites enabled
// libraryDependencySchemes := Seq("org.scala-lang.modules" %% "scala-xml" %VersionScheme.Always)
