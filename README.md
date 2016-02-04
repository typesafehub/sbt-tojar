# sbt-tojar

## Description

*sbt-tojar* is a simple sbt plugin that enables straight-to-jar compilation of Scala files while using sbt.

When compiling Scala files, sbt normally writes each generated class to a separate classfile, leading to
a potentially rather large number of individual small files. At the end of the compilation process,
all of those classfiles are packaged together into a new jar file in order to prepare for publishing.

The intermediate step of creating many individual classfiles can be somewhat slow on those filesystems
that are not very efficient at manipulating many small files.

This plugin will make use of scalac's straight-to-jar compilation feature, enabling sbt to avoid the
intermediate creation of the separate classfiles.

## Usage

* add to your project/plugin.sbt the line:
   `addSbtPlugin("com.typesafe.sbt" % "sbt-tojar" % "0.1")`
* then add to the settings of the subprojects for which you would like to enable this functionality:
   `straightToJar := true`

The straight-to-jar compilation feature can be enabled individually for each
subproject, and changed on-the-fly using a `set straightToJar in proj := value` (a `clean` may be
needed beforehand).

## Notes

Since the packaged jar file is generated directly by the Scala compiler, it will not be possible
to have in the same jar also files that are generated from other sources. Therefore, `straightTojar` cannot
be enabled on mixed Java/Scala subprojects, or on those subprojects that use `copyResources`.
However, in an sbt project that contains many different subprojects, the straight-to-jar compilation can
be enabled on all the subprojects that do not use those features.

It should also be noted that straight-to-jar compilation will disable the usual incremental compilation
mechanisms of sbt: if any source file in a straight-to-jar subproject is modified in a way that makes
recompilation necessary, then all of the source files of that subproject will be recompiled.

This plugin requires sbt 0.13.10, final or RC2+.
