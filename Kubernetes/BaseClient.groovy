// @Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7.1' )

import groovy.json.JsonBuilder
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovyx.net.http.HTTPBuilder
import groovyx.net.http.Method
import groovy.text.StreamingTemplateEngine

import static groovyx.net.http.ContentType.JSON
import static groovyx.net.http.Method.DELETE
import static groovyx.net.http.Method.GET
import static groovyx.net.http.Method.PATCH
import static groovyx.net.http.Method.POST
import static groovyx.net.http.Method.PUT

import static Logger.*

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.HttpsURLConnection;

class TrustManager implements X509TrustManager {
  public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null;  }
  public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
  public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
}

TrustManager[] trustAllCerts = new TrustManager[1]
trustAllCerts[0] = new TrustManager()
SSLContext sc = SSLContext.getInstance("SSL");
sc.init(null, trustAllCerts, new java.security.SecureRandom());
HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

/**
 * Groovy client with common utility functions used in a plugin procedure
 * step such as for making HTTP requests, error handling, etc.
 */

public class BaseClient {

    Object doHttpRequest(Method method, String requestUrl,
                         String requestUri, def requestHeaders,
                         boolean failOnErrorCode = true,
                         Object requestBody = null,
                         def queryArgs = null) {

        logger DEBUG, "Request details:\n  requestUrl: '$requestUrl' \n  method: '$method' \n  URI: '$requestUri'"
        if (queryArgs) {
            logger DEBUG, "queryArgs: '$queryArgs'"
        }
        logger DEBUG, "URL: '$requestUrl$requestUri'"
        if (requestBody) logger DEBUG, "Payload: $requestBody"

        def http = new HTTPBuilder(requestUrl)
        http.ignoreSSLIssues()

        http.request(method, JSON) {
            if (requestUri) {
                uri.path = requestUri
            }
            if (queryArgs) {
                uri.query = queryArgs
            }
            headers = requestHeaders
            body = requestBody

            response.success = { resp, json ->
                logger DEBUG, "request was successful $resp.statusLine.statusCode $json"
                [statusLine: resp.statusLine,
                 status: resp.status,
                 data      : json]
            }

            if (failOnErrorCode) {
                response.failure = { resp, reader ->
                    logger ERROR, "Response: $reader"
                    handleError("Request failed with $resp.statusLine")
                }
            } else {
                response.failure = { resp, reader ->
                    logger DEBUG, "Response: $reader"
                    logger DEBUG, "Response: $resp.statusLine"
                    [statusLine: resp.statusLine,
                     status: resp.status]
                }
            }
        }
    }

    def mergeObjs(def dest, def src) {
        //Converting both object instances to a map structure
        //to ease merging the two data structures
        logger DEBUG, "Source to merge: " + JsonOutput.toJson(src)
        def result = mergeJSON((new JsonSlurper()).parseText((new JsonBuilder(dest)).toString()),
                (new JsonSlurper()).parseText((new JsonBuilder(src)).toString()))
        logger DEBUG, "After merge: " + JsonOutput.toJson(result)
        return result
    }

    def mergeJSON(def dest, def src) {
        src.each { prop, value ->
            logger DEBUG, "Has property $prop? value:" + dest[prop]
            if(dest[prop] != null && dest[prop] instanceof Map) {
                mergeJSON(dest[prop], value)
            } else {
                dest[prop] = value
            }
        }
        return dest
    }

    Object parseJsonToList(Object data){
        if(!data){
            return []
        }

        try {
            def parsed = new JsonSlurper().parseText(data)
            if (!(parsed instanceof List)) {
                parsed = [ parsed ]
            }
            return parsed
        } catch (Exception e) {
            e.printStackTrace();
            handleError ("Cannot parse json: $data")
        }
    }

    /**
     * Based on plugin parameter value truthiness
     * True if value == true or value == '1'
     */
    boolean toBoolean(def value) {
        return value != null && (value == true || value == 'true' || value == 1 || value == '1')
    }

    def handleError (String msg) {
        println "ERROR: $msg"
        System.exit(-1)
    }

    def logger(Integer level, def message) {
        if ( level >= logLevel ) {
            println getLogLevelStr(level) + message
        }
    }

    String formatName(String name){
        return name.replaceAll(' ', '-').replaceAll('_', '-').replaceAll("^-+", "").replaceAll("-+\$", "").toLowerCase()
    }

    def removeNullKeys(obj) {
      if(obj instanceof Map) {
        obj.collectEntries {k, v ->
          if(v) [(k): removeNullKeys(v)] else [:]
        }
      } else if(obj instanceof List) {
        obj.collect { removeNullKeys(it) }.findAll { it != null }
      } else {
        obj
      }
    }

    def applyTemplate(String textTemplate, def binding) {
        def template = new StreamingTemplateEngine().createTemplate(textTemplate)
        template.make(binding).toString()
    }

    //Flag for use use during development if there is no internet access
    //in which case remote calls will become no-ops.
    //Should *never* be checked in with a value of true.
    final boolean OFFLINE = false

}
