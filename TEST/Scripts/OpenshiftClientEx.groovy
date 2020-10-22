public class OpenshiftClientEx extends KubernetesClient {
    def applyExtraResources(String clusterEndpoint, String namespace, String accessToken, def resourceMap) {
        resourceMap.each{ entry ->
            def resourceName = entry.key
            def resources = entry.value

            if(resourceName == 'buildconfigs' || resourceName == 'clusterrolebindings' ) {
                return
            }

            applyResouces(clusterEndpoint, namespace, accessToken, entry.key, entry.value)
        }

        def crb = resourceMap['clusterrolebindings']
        applyResouces(clusterEndpoint, namespace, accessToken, "clusterrolebindings", crb)

        def bcs = resourceMap['buildconfigs']
        applyResouces(clusterEndpoint, namespace, accessToken, "buildconfigs", bcs)
    }

    def applyResouces(String clusterEndpoint, String namespace, String accessToken, String resourceTypeName, def resources) {
        if(resources && !resources.empty){
            resources.each { res ->
                def resName = res.metadata.name
                if (!resName) { 
                    return
                }

                def apiPath = checkAPIPath(resourceTypeName,res)

                def response = doHttpGet(clusterEndpoint, "/${apiPath}/${namespace}/${resourceTypeName}/${resName}",accessToken, false) 
                if (response.status == 200) { 
                    logger INFO, "The ${resourceTypeName} $resName found in $namespace, updating ${resourceTypeName} ..."
                    applyResouce(response.data, resourceTypeName, resName, clusterEndpoint, namespace, res, accessToken)
                } else if (response.status == 404){ 
                    logger INFO, "The ${resourceTypeName}  $resName does not exist in $namespace, creating ${resourceTypeName} ..."
                    applyResouce(null, resourceTypeName, resName, clusterEndpoint, namespace, res, accessToken)
                } else {
                    handleError("${resourceTypeName} check failed. ${response.statusLine}")
                }            
            } 
        }
    }

    def applyResouce(def existingRes, String resourceTypeName, String resourceName, String clusterEndpoint, String namespace, def resource, String accessToken) {
        def payload = existingRes

        if (payload) { 
            payload =  new JsonBuilder(mergeObjs(payload, resource))
             logger INFO, "Updating ${resourceTypeName} : \n${payload.toPrettyString()}\n"
        } else {
            payload = new JsonBuilder(resource)
            logger INFO, "Creating ${resourceTypeName} : \n${payload.toPrettyString()}\n"
        }

        def apiPath = checkAPIPath(resourceTypeName,resource)
 
        def createRes = existingRes == null
        doHttpRequest(createRes ? POST : PUT,
                    clusterEndpoint,
                    createRes?
                            "/${apiPath}/${namespace}/${resourceTypeName}" :
                            "/${apiPath}/${namespace}/${resourceTypeName}/${resourceName}",
                    ['Authorization' : accessToken], true,payload.toPrettyString()) 
    }

    String checkAPIPath(String resourceTypeName, def resource) {
        if(resource.apiVersion == 'v1') {
             return "api/v1/namespaces"
        } else {
            return "apis/${resource.apiVersion}/namespaces"
        }
    } 

    def startBuild(String clusterEndpoint, String namespace, String accessToken, def buildConfigName) {
        def payload = buildPayload(buildConfigName)

        println "payload : ${payload}"

        doHttpRequest(POST,
                clusterEndpoint,
                "/apis/build.openshift.io/v1/namespaces/${namespace}/buildconfigs/${buildConfigName}/instantiate",
                ['Authorization' : accessToken], true, payload)
    }

    String buildPayload(String buildConfigName){
        def json = new JsonBuilder()
        def result = json{
            kind "BuildRequest"
            apiVersion "build.openshift.io/v1"
            metadata {
                name buildConfigName
            }
            triggeredBy (
                [
                    {
                        message("Manually triggered")
                    }
                ]
            )
            dockerStrategyOptions {
            }
            sourceStrategyOptions {
            }
        }
        return (new JsonBuilder(result)).toPrettyString()
    }

}