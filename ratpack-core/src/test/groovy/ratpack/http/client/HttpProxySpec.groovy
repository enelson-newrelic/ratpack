/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.http.client

import spock.lang.Unroll

import java.util.zip.GZIPInputStream

import static ratpack.http.ResponseChunks.stringChunks
import static ratpack.http.internal.HttpHeaderConstants.CONTENT_ENCODING
import static ratpack.stream.Streams.publish

@Unroll
class HttpProxySpec extends BaseHttpClientSpec {

  def "can proxy a client response"() {
    given:
    bindings {
      bindInstance(HttpClient, HttpClient.of { it.poolSize(pooled ? 8 : 0) })
    }
    otherApp {
      get("foo") {
        response.headers.add("x-foo-header", "foo")
        render "bar"
      }
    }

    and:
    handlers {
      get { HttpClient httpClient ->
        httpClient.requestStream(otherAppUrl("foo")) {
        } then {
          it.forwardTo(response)
        }
      }
    }

    expect:
    rawResponse() == """HTTP/1.1 200 OK
x-foo-header: foo
content-type: text/plain;charset=UTF-8$keepalive
transfer-encoding: chunked

3
bar
0

"""

    where:
    pooled << [true, false]
    keepalive << ["\nconnection: keep-alive", ""]
  }

  def "can proxy a client chunked response"() {
    given:
    bindings {
      bindInstance(HttpClient, HttpClient.of { it.poolSize(pooled ? 8 : 0) })
    }
    otherApp {
      get("foo") {
        response.headers.add("x-foo-header", "foo")
        render stringChunks(
          publish(["bar"] * 3)
        )
      }
    }

    and:
    handlers {
      get { HttpClient httpClient ->
        httpClient.requestStream(otherAppUrl("foo")) {
        } then {
          it.forwardTo(response)
        }
      }
    }

    expect:
    rawResponse() == """HTTP/1.1 200 OK
x-foo-header: foo
content-type: text/plain;charset=UTF-8$keepalive
transfer-encoding: chunked

3
bar
3
bar
3
bar
0

"""

    where:
    pooled << [true, false]
    keepalive << ["\nconnection: keep-alive", ""]
  }

  def "can mutate response headers while proxying"() {
    given:
    bindings {
      bindInstance(HttpClient, HttpClient.of { it.poolSize(pooled ? 8 : 0) })
    }
    otherApp {
      get("foo") {
        response.headers.add("x-foo-header", "foo")
        render stringChunks(
          publish(["bar"] * 3)
        )
      }
    }

    and:
    handlers {
      get { HttpClient httpClient ->
        httpClient.requestStream(otherAppUrl("foo")) {
        } then {
          it.forwardTo(response) { it.remove("x-foo-header").add("x-bar-header", "bar") }
        }
      }
    }

    expect:
    rawResponse() == """HTTP/1.1 200 OK
content-type: text/plain;charset=UTF-8$keepalive
x-bar-header: bar
transfer-encoding: chunked

3
bar
3
bar
3
bar
0

"""

    where:
    pooled << [true, false]
    keepalive << ["\nconnection: keep-alive", ""]
  }

  def "can proxy a client error"() {
    given:
    bindings {
      bindInstance(HttpClient, HttpClient.of { it.poolSize(pooled ? 8 : 0) })
    }
    otherApp {
      get("foo") {
        response.headers.add("x-foo-header", "foo")
        clientError(404)
      }
    }

    and:
    handlers {
      get { HttpClient httpClient ->
        httpClient.requestStream(otherAppUrl("foo")) {
        } then {
          it.forwardTo(response)
        }
      }
    }

    expect:
    rawResponse() == """HTTP/1.1 404 Not Found
x-foo-header: foo
content-type: text/plain$keepalive
transfer-encoding: chunked

10
Client error 404
0

"""

    where:
    pooled << [true, false]
    keepalive << ["\nconnection: keep-alive", ""]
  }

  def "can proxy a server error"() {
    given:
    bindings {
      bindInstance(HttpClient, HttpClient.of { it.poolSize(pooled ? 8 : 0) })
    }
    otherApp {
      get("foo") {
        response.headers.add("x-foo-header", "foo")
        error(new Throwable("A server error occurred"))
      }
    }

    and:
    handlers {
      get { HttpClient httpClient ->
        httpClient.requestStream(otherAppUrl("foo")) {
        } then {
          it.forwardTo(response)
        }
      }
    }

    expect:
    rawResponse().with {
      startsWith("""HTTP/1.1 500 Internal Server Error
x-foo-header: foo
content-type: text/plain
connection: keep-alive
transfer-encoding: chunked
""")
      contains("A server error occurred")
    }

    where:
    pooled << [true, false]
  }

  def "can proxy compressed responses"() {
    given:
    bindings {
      bindInstance(HttpClient, HttpClient.of { it.poolSize(pooled ? 8 : 0) })
    }
    otherApp {
      get("foo") {
        render "bar"
      }
    }

    and:
    handlers {
      get { HttpClient httpClient ->
        httpClient.request(otherAppUrl("foo")) {
          it.decompressResponse(false)
            .headers.copy(request.headers)
        } then {
          it.forwardTo(response)
        }
      }
    }

    when:
    def response = requestSpec {
      it.decompressResponse(false)
        .headers.add("Accept-Encoding", "compress, gzip")
    }.get()

    then:
    response.headers.get(CONTENT_ENCODING) == "gzip"
    new GZIPInputStream(response.body.inputStream).bytes == "bar".bytes

    where:
    pooled << [true, false]
  }

  def "can proxy a post request"() {
    given:
    bindings {
      bindInstance(HttpClient, HttpClient.of { it.poolSize(pooled ? 8 : 0) })
    }
    otherApp {
      post("foo") {
        render request.body.map { it.text }
      }
    }

    and:
    handlers {
      post { HttpClient httpClient ->
        request.body.flatMap { incoming ->
          httpClient.request(otherAppUrl("foo")) { it.method("POST").body.buffer(incoming.buffer) }
        } then {
          it.forwardTo(response)
        }
      }
    }

    when:
    def response = requestSpec { it.body.text("bar") }.post()

    then:
    response.body.text == "bar"

    where:
    pooled << [true, false]
  }
}
