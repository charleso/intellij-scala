package org.jetbrains.plugins.scala
package project.notification.source

import com.intellij.jarFinder.SourceSearcher
import com.intellij.openapi.progress.ProgressIndicator

sealed trait SourceResolver {
  def toURL(groupId: String, artifactId: String, version: String): String
  def append(s: String, s2: String): String =
    (if (s.endsWith("/")) s else s + "/") + (if (s2.startsWith("/")) s2.substring(1) else s2)
}

case class IvyResolver(base: String) extends SourceResolver {
  override def toURL(groupId: String, artifactId: String, version: String): String =
    append(base, groupId) + "/" + artifactId + "/" + version + "/srcs/" + artifactId + "-sources.jar"
}

case class MavenResolver(base: String) extends SourceResolver {
  override def toURL(groupId: String, artifactId: String, version: String): String =
    append(base, groupId.replace(".", "/")) + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + "-sources.jar"
}

class SBTSourceSearcher(l: List[SourceResolver]) extends SourceSearcher {

  override def findSourceJar(indicator: ProgressIndicator, fullArtifactId: String, version: String): String = {
    indicator.setIndeterminate(true)
    indicator.checkCanceled()
    val Array(groupId, artifactId) = fullArtifactId.split("\n", 2)
    l.toStream.map {
      resolver =>
        val url = resolver.toURL(groupId, artifactId, version)
        indicator.setText(s"Connecting to $url")
        val connection = new java.net.URL(url).openConnection().asInstanceOf[java.net.HttpURLConnection]
        try {
          connection.setRequestMethod("HEAD")
          if (connection.getResponseCode != 200) None
          else Some(url)
        } finally
          connection.disconnect()
    }.find(_.isDefined).flatten.orNull
  }
}
