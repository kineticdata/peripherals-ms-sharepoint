import java.text.SimpleDateFormat
import java.util.Date
plugins {
    id("net.nemerosa.versioning") version "2.14.0"
    java
    `maven-publish`
}

repositories {
    mavenLocal()
  maven {
    url = uri("https://s3.amazonaws.com/maven-repo-public-kineticdata.com/releases")
  }

  maven {
    url = uri("s3://maven-repo-private-kineticdata.com/releases")
    authentication {
      create<AwsImAuthentication>("awsIm")
    }

  }

  maven {
    url = uri("https://s3.amazonaws.com/maven-repo-public-kineticdata.com/snapshots")
  }

  maven {
    url = uri("s3://maven-repo-private-kineticdata.com/snapshots")
    authentication {
      create<AwsImAuthentication>("awsIm")
    }
  }

  maven {
    url = uri("https://repo.maven.apache.org/maven2/")
  }

  maven {
    url = uri("https://repo.springsource.org/release/")
  }

  maven {
    url = uri("https://build.shibboleth.net/nexus/content/repositories/releases/")
  }
}

dependencies {
    implementation("com.kineticdata.filehub:kinetic-filehub-adapter:1.1.0")
    implementation("org.apache.httpcomponents:httpclient:4.5.1")
    implementation("org.apache.httpcomponents:httpmime:4.5.1")
    implementation("com.googlecode.json-simple:json-simple:1.1.1")
    implementation("com.google.guava:guava:18.0")
    implementation("commons-io:commons-io:2.2")
    implementation("org.apache.tika:tika-core:1.19")
    implementation("org.slf4j:slf4j-api:2.0.7")
    implementation("org.slf4j:slf4j-reload4j:2.0.7")
    testImplementation("junit:junit:4.10")
}

group = "com.kineticdata.filehub.adapters.sharepoint"
version = "1.0.1"
description = "kinetic-filehub-adapter-sharepoint"
java.sourceCompatibility = JavaVersion.VERSION_1_8

publishing {
  repositories {
    maven {
      val releasesUrl = uri("s3://maven-repo-private-kineticdata.com/releases")
      val snapshotsUrl = uri("s3://maven-repo-private-kineticdata.com/snapshots")
      url = if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
      authentication {
        create<AwsImAuthentication>("awsIm")
      }
    }
  }
    publications.create<MavenPublication>("maven") {
        from(components["java"])
    }
}

tasks.withType<JavaCompile>() {
    options.encoding = "UTF-8"
}
versioning {
  gitRepoRootDir = "../../"
}
tasks.processResources {
  duplicatesStrategy = DuplicatesStrategy.INCLUDE
  val currentDate = SimpleDateFormat("yyyy-MM-dd").format(Date())
  from("src/main/resources"){
    filesMatching("**/*.version") {    
      expand(    
        "buildNumber" to versioning.info.build,
        "buildDate" to currentDate,    
        "timestamp" to System.currentTimeMillis(),    
        "version" to project.version    
      )    
    }
  }
}
