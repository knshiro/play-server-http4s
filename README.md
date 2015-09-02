# play-server-http4s

[![Build Status](https://travis-ci.org/knshiro/play-server-http4s.svg)](https://travis-ci.org/knshiro/play-server-http4s)

Experimental [http4s](http://http4s.org/) backend for [Play framework](https://www.playframework.com/).
This is just a proof of concept for the moment and needs work.

## How to use
To use the Akka HTTP server backend you first need to disable the Netty server and add the http4s HTTP server dependency to your project:

```scala
lazy val root = (project in file("."))
  .disablePlugins(PlayNettyServer)

resolvers += "Knshiro's repository" at "https://dl.bintray.com/knshiro/maven"

libraryDependencies += "me.ugo" %% "play-server-http4s" % "0.0.4"
```

## Injected values

Middlewares can inject values in requests by the mean of Request.tags. It's
limited to `String` right now due to Play `Request` model.
Example:
```
import play.api._
import play.api.mvc._
import me.ugo.http4s.middleware.Referrals

class Application @Inject() (implicit ec: ExecutionContext) extends Controller {

  def getReferralTag = Action { request =>
    val referringSearchEngine = request.get(Referrals.referringSearchEngine.name).getOrElse("No referring search engine")    
    val referringSearchTerms = request.get(Referrals.referringSearchEngine.name).map(_.split(",")).getOrElse("No referring search engine")    
    Ok(referringSearchEngine + ":\n" + referringSearchTerms.mkString("\n"))
  }

}

```

## TODO

- SSL
- Websockets
- Better enumerator to stream (and vice-versa) transformations.
