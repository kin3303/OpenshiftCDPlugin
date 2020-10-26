@Grab('org.yaml:snakeyaml:1.19')
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.yaml.snakeyaml.Yaml

public class ImportFromTemplate extends EFClient {
    static final String CREATED_DESCRIPTION = "Created by ImportFromTemplate"
    //static final String DELIMITER = "---"
    static def REPORT_TEMPLATE = '''
    $[/myProject/resources/report]
    '''
    static def REPORT_URL_PROPERTY = '/myJob/report-urls/'

    static def KIND_ROUTE = 'Route'
    static def KIND_SERVICE = 'Service'

    def ignoreList = []
    def discoveredSummary = [:]
    def parsedConfigList = []

    Yaml parser = new Yaml()

    def resolveTemplateByParameters(template, parametersMap) {
        parametersMap = parametersMap ?: [:]
        def parametersMapCloned = parametersMap.clone();

        // checking whether there are parameters with default values defined in template
        Yaml parser = new Yaml()
        def parsedTemplate = parser.load(template)
        def parametersWithDefaultValuesFromTemplate = [:]
        parsedTemplate.parameters.each { item ->
            if (item.containsKey('value')) {
                parametersWithDefaultValuesFromTemplate.put(item.name, item.value)
            }

        }

        // expand parameters map by default values for not provided parameters
        parametersWithDefaultValuesFromTemplate.each { parameterName, parameterValue ->
            if (!parametersMapCloned.containsKey(parameterName)) {
                logger INFO, "Parameter '$parameterName' is not provided, using its default value from template: '$parameterValue'"
                parametersMapCloned.put(parameterName, parameterValue)
            }
        }

        // resolve the template by provided parameters or default values
        def resolvedTemplate = template
        parametersMapCloned.each { parameterName, parameterValue ->
            resolvedTemplate = resolvedTemplate.replaceAll(/\$\{{1,2}\s*${parameterName}\s*\}{1,2}/, parameterValue)
        }

        return resolvedTemplate
    }

    def importFromTemplate(fileYAML){
        def efServices = []
        def configList = fileYAML

        def parsedConfig = parser.load(configList)

        parsedConfig.objects.each{ obj ->
            parsedConfigList.push(obj)
        }

        def services
        try {
            services = getParsedServices(parsedConfigList)
        }
        catch(Exception e) {
            println "Failed to find any services in the YAML file. Cause: ${e.message}"
            System.exit(-1)
        }
        logger INFO, "Parsed services: ${services}"

        def deployments
        try {
            deployments = getParsedDeployments(parsedConfigList)
        }
        catch(Exception e) {
            println "Failed to find any deployment configurations in the YAML file. Cause: ${e.message}"
            System.exit(-1)
        }

        if (deployments.size() < 1){
            println "Failed to find any deployment configurations in the YAML file."
            System.exit(-1)
        }
        logger INFO, "Parsed deployments: ${deployments}"

        services.each { kubeService ->
            if (!isSystemService(kubeService)) {
                def allQueryDeployments = []
                def i = 0
                kubeService.spec.selector.each{ k, v ->
                    i = i + 1
                    def queryDeployments = getDeploymentsBySelector(deployments, k, v)
                    if (queryDeployments != null) {
                        queryDeployments.eachWithIndex { deployment, index ->
                            if (index == 0) {
                                allQueryDeployments.push(deployment)
                            } else {
                                logger INFO, "More than one deployment (${deployment.metadata.name}) with the matching label was found for service ${kubeService.metadata.name} with selector ( ${k} : ${v} )."
                            }
                        }
                    }
                }
                def dedupedQueryDeployments = []
                allQueryDeployments.each{ deploy ->
                    def uniq = true
                    dedupedQueryDeployments.each { dedupedDeploy ->
                        if (deploy.metadata.name == dedupedDeploy.metadata.name){
                            uniq = false
                        }
                    }
                    if (uniq){
                        dedupedQueryDeployments.push(deploy)
                    }
                }
                dedupedQueryDeployments.eachWithIndex { deploy, indexDeploy ->
                    def efService = buildServiceDefinition(kubeService, deploy)
                    efServices.push(efService)
                }
            }
        }

        if(ignoreList){
            def textTable = buildReportText(ignoreList)
            publishReport(textTable)
        }

        efServices
    }

    def flattenMap(keyPrefix, input, LinkedHashMap flatten ) {
        input.each { k, v ->
            def key = "${keyPrefix}/${k}".trim()
            if (v instanceof LinkedHashMap) {
                flattenMap(key, v, flatten)
            }
            else if (v instanceof ArrayList){
                flatten = flattenArrayList(key, v, flatten)
            }
            else {
                flatten[key] = v
            }
        }

        flatten
    }

    def flattenArrayList(keyPrefix, value, flatten ){
        value.eachWithIndex{ v, i ->
            def key = "${keyPrefix}[${i}]".trim()
            if (v instanceof LinkedHashMap) {
                flattenMap(key, v, flatten)
            }
            else if (v instanceof ArrayList){

                flattenArrayList(key, v, flatten)
            }
            else{
                flatten[key] = v
            }
        }
        flatten
    }

    def getParsedServices(parsedConfigList){
        def services = []
        parsedConfigList.each { config ->
            if (config.kind == "Service"){
                services.push(config)
            }
        }
        services
    }

    def getParsedDeployments(parsedConfigList){
        def deployments = []
        parsedConfigList.each { config ->
            if (config.kind in ['Deployment','DeploymentConfig']){
                deployments.push(config)
            }
        }
        deployments
    }

    def getDeploymentsBySelector(deployments, key, value){
        def queryDeployments = []
        def first = true
        deployments.each { deployment ->
            def removeLabels = []
            deployment.spec?.template?.metadata?.labels.each{ k, v ->
                if ((k == key) && (v == value)){
                    if (first){
                        queryDeployments.push(deployment)
                        removeLabels.push(k)
                        first = false
                    }
                    else{
                        queryDeployments.push(deployment)
                    }
                }
            }
            if (removeLabels){
                removeLabels.each { keyToRemove ->
                    deployment.spec?.template?.metadata?.labels.remove(keyToRemove)
                }
            }
        }
        queryDeployments
    }

    def getExistingApp(applicationName, projectName){
        def applications = getApplications(projectName)
        def existingApplication = applications?.find {
            it.applicationName == applicationName && it.projectName == projectName
        }
        existingApplication
    }

    def saveToEF(services, projectName, envProjectName, envName, clusterName, String applicationName = null) {
        if (applicationName && !getExistingApp(applicationName, projectName)){
            def app = createApplication(projectName, applicationName)
            logger INFO, "Application ${applicationName} has been created"
            createAppDeployProcess(projectName, applicationName)
            // create link for the application
            def applicationId = app.applicationId
            setEFProperty("/myJob/report-urls/Application: $applicationName", "/flow/#applications/$applicationId")
        }

        def efServices = getServices(projectName, applicationName)
        services.each { service ->
            def svc = createOrUpdateService(projectName, envProjectName, envName, clusterName, efServices, service, applicationName)
            // create links for the service if creating top-level services
            if (svc && !applicationName) {
                def serviceId = svc.serviceId
                setEFProperty("/myJob/report-urls/Microservice: ${svc.serviceName}", "/flow/#services/$serviceId")
            }
        }

        def lines = ["Discovered services: ${discoveredSummary.size()}"]
        discoveredSummary.each { serviceName, containers ->
            def containerNames = containers.collect { k -> k.key }
            if (applicationName) {
                lines.add("${applicationName}: ${serviceName}: ${containerNames.join(', ')}")
            } else {
                lines.add("${serviceName}: ${containerNames.join(', ')}")
            }
        }
        updateJobSummary(lines.join("\n"), /*jobStepSummary*/ true)
    }

     def setPropertyInExistingMicroservice(String projectName, String applicationName, String serviceName, String propertyName, String value) { 
        def payload = [:] 
        payload << [
            value: value,
            projectName: projectName,
            applicationName: applicationName,
            serviceName: serviceName
        ]
        payload = JsonOutput.toJson(payload)
 
        doHttpPut("/rest/v1.0/properties/${propertyName}", payload)
    }   

     def getPropertyInExistingMicroservice(String projectName, String applicationName, String serviceName, String propertyName) { 
 
        def result=getEFProperty("/projects/${projectName}/applications/${applicationName}/services/${serviceName}/${propertyName}", /*ignoreError*/ true)

        print result
        //def mapPayload = new JsonSlurper().parseText(new String(result.property.value))

        return result
    }   

    def createOrUpdateService(projectName, envProjectName, envName, clusterName, efServices, service, String applicationName = null) {
        def existingService = efServices.find { s ->
            equalNames(s.serviceName, service.service.serviceName)
        }
        def result
        def serviceName

        logger DEBUG, "Service payload:"
        logger DEBUG, new JsonBuilder(service).toPrettyString()

        if (existingService) {
            serviceName = existingService.serviceName
            logger WARNING, "Service ${existingService.serviceName} already exists, skipping"
            // return null
            // Future
            // result = updateEFService(existingService, service)
            // logger INFO, "Service ${existingService.serviceName} has been updated"
        }
        else {
            serviceName = service.service.serviceName
            result = createEFService(projectName, service, applicationName)?.service
            logger INFO, "Service ${serviceName} has been created"
            discoveredSummary[serviceName] = [:]
        }
        assert serviceName

        if(!existingService) {
            // Containers
            service.secrets?.each { cred ->
                def credName = getCredName(cred)
                createCredential(projectName, credName, cred.userName, cred.password)
                logger INFO, "Credential $credName has been created"
            }

            service.containers.each { container ->
                service.secrets?.each { secret ->
                    if (secret.repoUrl =~ /${container.container.registryUri}/) {
                        container.container.credentialName = getCredName(secret)
                    }
                }
                createOrUpdateContainer(projectName, serviceName, container, applicationName)
                mapContainerPorts(projectName, serviceName, container, service, applicationName)
            }
            if (service.serviceMapping && envProjectName && envName && clusterName) {
                createOrUpdateMapping(projectName, envProjectName, envName, clusterName, serviceName, service, applicationName)
            }

            if (applicationName) {
                createAppDeployProcessStep(projectName, applicationName, serviceName)
            }
        } else {
            if (service.serviceMapping && envProjectName && envName && clusterName) {
                println  '-------->testPoint2'
                createOrUpdateMapping(projectName, envProjectName, envName, clusterName, serviceName, service, applicationName)
                println '<--------testPoint2'
            }
        }

        saveDeploymentsToServiceObject(projectName, applicationName, service.service.serviceName)
        
        result
    }

    def createAppDeployProcess(projectName, applicationName) {
        def processName = 'Deploy'
        createAppProcess(projectName, applicationName, [processName: processName, processType: 'DEPLOY'])
        logger INFO, "Process ${processName} has been created for applicationName: '${applicationName}'"
    }

    def createAppDeployProcessStep(projectName, applicationName, serviceName) {
        def processName = 'Deploy'
        def processStepName = "deployService-${serviceName}"
        createAppProcessStep(projectName, applicationName, processName, [
                processStepName: processStepName,
                processStepType: 'service', subservice: serviceName]
        )
        logger INFO, "Process step ${processStepName} has been created for process ${processName} in service ${serviceName}"
    }

    def createOrUpdateMapping(projName, envProjName, envName, clusterName, serviceName, service, applicationName = null) {
        assert envProjName
        assert envName
        assert clusterName

        def mapping = service.serviceMapping

        def existingMap = getExistingMapping(projName, serviceName, envProjName, envName, applicationName)

        def envMapName
        if (existingMap && !applicationName) {
            logger INFO, "Environment map already exists for service ${serviceName} and cluster ${clusterName}"
            envMapName = existingMap.environmentMapName
        }
        else if(existingMap && applicationName){
            logger INFO, "Environment map already exists for service ${serviceName} in application ${applicationName} and cluster ${clusterName}"
            envMapName = existingMap.tierMapName
        }
        else if (applicationName){
            def payload = [
                    environmentProjectName: envProjName,
                    environmentName: envName,
                    tierMapName: "${applicationName}-${envName}",
                    description: CREATED_DESCRIPTION,
            ]

            def result = createTierMap(projName, applicationName, payload)
            envMapName = result.tierMap?.tierMapName
        }
        else {
            def payload = [
                environmentProjectName: envProjName,
                environmentName: envName,
                description: CREATED_DESCRIPTION,
            ]

            def result = createEnvMap(projName, serviceName, payload)
            envMapName = result.environmentMap?.environmentMapName
        }

        assert envMapName

        def existingClusterMapping = existingMap?.serviceClusterMappings?.serviceClusterMapping?.find {
            it.clusterName == clusterName
            it.serviceName == serviceName
        }

        def serviceClusterMappingName
        if (existingClusterMapping) {
            logger INFO, "Cluster mapping already exists"
            serviceClusterMappingName = existingClusterMapping.serviceClusterMappingName
        }
        else {
            def payload = [
                clusterName: clusterName,
                environmentName: envName,
                environmentProjectName: envProjName
            ]
            if (mapping) {
                def actualParameters = []
                mapping.each {k, v ->
                    if (v) {
                        actualParameters.add([actualParameterName: k, value: v])
                    }
                }
                payload.actualParameter = actualParameters
            }
            def result
            if (applicationName){
                payload.serviceName = "${serviceName}"
                payload.serviceClusterMappingName = "${clusterName}-${serviceName}"

                logger INFO, pretty(payload)
                result = createAppServiceClusterMapping(projName, applicationName, envMapName, payload)
                logger INFO, "Created Service Cluster Mapping for ${serviceName} in application ${applicationName} and ${clusterName}"
                serviceClusterMappingName = result.serviceClusterMapping.serviceClusterMappingName
            }
            else{
                result = createServiceClusterMapping(projName, serviceName, envMapName, payload)
                logger INFO, "Created Service Cluster Mapping for ${serviceName} and ${clusterName}"
                serviceClusterMappingName = result.serviceClusterMapping.serviceClusterMappingName
            }
        }

        assert serviceClusterMappingName
        service.containers?.each { container ->
            def payload = [
                containerName: container.container.containerName
            ]
            if (container.mapping) {
                def actualParameters = []
                container.mapping.each {k, v ->
                    if (v) { 
                        actualParameters.add(
                            [actualParameterName: k, value: v]
                        )
                    }
                }
                payload.actualParameter = actualParameters
                //logger INFO, 'createServiceMapDetails Parameters:'
                //logger INFO, pretty(payload)
            }
            if(applicationName){
                createServiceMapDetails2(
                    projName,
                    serviceName,
                    envMapName,
                    serviceClusterMappingName,
                    payload,
                    applicationName
                )
            } else {
                createServiceMapDetails(
                    projName,
                    serviceName,
                    envMapName,
                    serviceClusterMappingName,
                    payload,
                    applicationName
                )              
            }
        }
    }

    def getExistingTierMap(applicationName, envName, envProjectName, projectName){
        def tierMaps = getTierMaps(projectName, applicationName)
        def existingTierMap = tierMaps?.find {
            it.applicationName == applicationName && it.environmentName == envName && it.environmentProjectName == envProjectName && it.projectName == projectName
        }
        existingTierMap
    }

    def getExistingEnvMap(projectName, serviceName, envProjectName, envName){
        def envMaps
        envMaps = getEnvMaps(projectName, serviceName)
        def existingMap = envMaps.environmentMap?.find {
            it.environmentProjectName == envProjectName && it.projectName == projectName && it.serviceName == serviceName && it.environmentName == envName
        }
        existingMap
    }

    def getExistingMapping(projectName, serviceName, envProjectName, envName, applicationName = null) {
        def existingServiceMapping
        if (applicationName){
            existingServiceMapping = getExistingTierMap(applicationName, envName, envProjectName, projectName)
        }
        else{
            existingServiceMapping = getExistingEnvMap(projectName, serviceName, envProjectName, envName)
        }
        existingServiceMapping
    }

    def isBoundPort(containerPort, servicePortMeta) {
        if (containerPort.portName == servicePortMeta.portName) {
            return true
        }
        if (servicePortMeta.targetPort =~ /^\d+$/ && servicePortMeta.targetPort == containerPort.containerPort) {
            return true
        }
        return false
    }

    def mapContainerPorts(projectName, serviceName, container, service, applicationName = null) {
        container.ports?.each { containerPort ->
            service.portsMeta?.each { servicePortMeta ->
                if (isBoundPort(containerPort, servicePortMeta)) {
                    def portName = servicePortMeta.portNameRaw ? servicePortMeta.portNameRaw : "servicehttp${serviceName}${container.container.containerName}${containerPort.containerPort}"
                    def generatedPort = [
                        portName: portName,
                        listenerPort: servicePortMeta.listenerPort,
                        subcontainer: container.container.containerName,
                        subport: containerPort.portName
                    ]
                    createPort(projectName, serviceName, generatedPort, null, false, applicationName)
                    logger INFO, "Port ${portName} has been created for service ${serviceName}, listener port: ${generatedPort.listenerPort}, container port: ${generatedPort.subport}"
                }
            }
        }
    }

    def createOrUpdateContainer(projectName, serviceName, container, applicationName = null) {
        logger DEBUG, "Container payload:"
        logger DEBUG, new JsonBuilder(container).toPrettyString()

        def containerName = container.container.containerName
        assert containerName
        logger INFO, "Going to create container ${serviceName}/${containerName}"
        logger INFO, pretty(container.container)

        createContainer(projectName, serviceName, container.container, applicationName)
        logger INFO, "Container ${serviceName}/${containerName} has been created"
        discoveredSummary[serviceName] = discoveredSummary[serviceName] ?: [:]
        discoveredSummary[serviceName][containerName] = [:]

        container.ports.each { port ->
            createPort(projectName, serviceName, port, containerName, false, applicationName)
            logger INFO, "Port ${port.portName} has been created for container ${containerName}, container port: ${port.containerPort}"
        }

        if (container.env) {
            container.env.each { env ->
                createEnvironmentVariable(projectName, serviceName, containerName, env, false, applicationName)
                logger INFO, "Environment variable ${env.environmentVariableName} has been created"
            }
        }

    }

    def buildSecretsDefinition(namespace, secrets) {
        def retval = []
        secrets.each {
            def name = it.name
            def secret = kubeClient.getSecret(name, clusterEndpoint, namespace, accessToken)

            def dockercfg = secret.data['.dockercfg']
            if (dockercfg) {
                def decoded = new JsonSlurper().parseText(new String(dockercfg.decodeBase64(), "UTF-8"))

                if (decoded.keySet().size() == 1) {
                    def repoUrl = decoded.keySet().first()
                    def username = decoded[repoUrl].username
                    def password = decoded[repoUrl].password

                    // Password may be absent
                    // In this case we can do nothing

                    if (password) {
                        def cred = [
                            repoUrl: repoUrl,
                            userName: username,
                            password: password
                        ]
                        retval.add(cred)
                    }
                    else {
                        logger WARNING, "Cannot retrieve password from secret for $repoUrl, please create a credential manually"
                    }
                }
            }
        }
        retval
    }

    def buildServiceDefinition(kubeService, deployment){

        def logService = []
        def logDeployment = []
        def serviceName = kubeService.metadata.name
        def deployName = deployment.metadata.name

        logService.push("/kind".trim())
        logService.push("/apiVersion".trim())
        logService.push("/metadata/name".trim())
        kubeService.spec.selector.each{key, value->
            logService.push("/spec/selector/${key}".trim())
        }

        logDeployment.push("/apiVersion".trim())
        logDeployment.push("/kind".trim())
        logDeployment.push("/metadata/name".trim())
        deployment.metadata.labels.each{key, value ->
            logDeployment.push("/metadata/labels/${key}".trim())
        }

        def efServiceName
        if (serviceName =~ /(?i)${deployName}/) {
            efServiceName = serviceName
        }
        else {
            efServiceName = "${serviceName}-${deployName}"
        }
        def efService = [
            service: [
                serviceName: efServiceName
            ],
            serviceMapping: [:]
        ]

        // Service Fields
        def defaultCapacity = deployment.spec?.replicas ?: 1
        logDeployment.push("/spec/replicas")
        efService.service.defaultCapacity = defaultCapacity

        if (deployment.spec?.strategy?.rollingUpdate) {
            def rollingUpdate = deployment.spec.strategy.rollingUpdate

            logDeployment.push("/spec/strategy/rollingUpdate/maxSurge")

            if (rollingUpdate.maxSurge =~ /%/) {

                efService.serviceMapping.with {
                    deploymentStrategy = 'rollingDeployment'
                    maxRunningPercentage = getMaxRunningPercentage(rollingUpdate.maxSurge)
                }
            }
            else {
                efService.service.maxCapacity = getMaxCapacity(defaultCapacity, rollingUpdate.maxSurge)
            }

            logDeployment.push("/spec/strategy/rollingUpdate/maxUnavailable")
            if (rollingUpdate.maxUnavailable =~ /%/) {
                efService.serviceMapping.with {
                    minAvailabilityPercentage = getMinAvailabilityPercentage(rollingUpdate.maxUnavailable)
                    deploymentStrategy = 'rollingDeployment'
                }
            }
            else {
                efService.service.minCapacity = getMinCapacity(defaultCapacity, rollingUpdate.maxUnavailable)
            }

        }
        logService.push("/spec/clusterIP")
        logService.push("/spec/type")
        logService.push("/spec/sessionAffinity")
        logService.push("/spec/loadBalancerSourceRanges")

        def mapping = buildServiceMapping(kubeService)
        mapping.each { k, v ->
            efService.serviceMapping[k] = v;
        }

        // Ports
        def portInd = 0
        efService.portsMeta = kubeService.spec?.ports?.collect { port ->
            def name
            if (port.targetPort) {
                name = port.targetPort as String
            }
            else {
                name = "${port.protocol}${port.port}"
            }
            logService.push("/spec/ports[${portInd}]/name")
            logService.push("/spec/ports[${portInd}]/protocol")
            logService.push("/spec/ports[${portInd}]/port")
            logService.push("/spec/ports[${portInd}]/targetPort")
            portInd += 1
            [portName: name.toLowerCase(), listenerPort: port.port, targetPort: port.targetPort, portNameRaw: port.name]
        }

        // Containers
        def containers = deployment.spec.template.spec.containers
        containers.eachWithIndex{ kubeContainer, index ->
            logDeployment.push("/spec/template/spec/containers[${index}]/name")
            logDeployment.push("/spec/template/spec/containers[${index}]/image")
            kubeContainer?.command.eachWithIndex{ singleCommand, ind ->
                logDeployment.push("/spec/template/spec/containers[${index}]/command[${ind}]")
            }
            kubeContainer?.args.eachWithIndex{ arg, ind ->
                logDeployment.push("/spec/template/spec/containers[${index}]/args[${ind}]")
            }
            logDeployment.push("/spec/template/spec/containers[${index}]/resources/limits/memory")
            logDeployment.push("/spec/template/spec/containers[${index}]/resources/limits/cpu")
            logDeployment.push("/spec/template/spec/containers[${index}]/resources/requests/memory")
            logDeployment.push("/spec/template/spec/containers[${index}]/resources/requests/cpu")
            kubeContainer?.ports.eachWithIndex{ port, ind ->
                logDeployment.push("/spec/template/spec/containers[${index}]/ports[${ind}]/containerPort")
            }
            kubeContainer?.volumeMounts.eachWithIndex{ volume, ind ->
                logDeployment.push("/spec/template/spec/containers[${index}]/volumeMounts[${ind}]/name")
                logDeployment.push("/spec/template/spec/containers[${index}]/volumeMounts[${ind}]/mountPath")
            }
            kubeContainer?.env.eachWithIndex{ singleEnv, ind ->
                logDeployment.push("/spec/template/spec/containers[${index}]/env[${ind}]/name")
                logDeployment.push("/spec/template/spec/containers[${index}]/env[${ind}]/value")
            }

        }
        efService.containers = containers.collect { kubeContainer ->
            def container = buildContainerDefinition(kubeContainer)//, logDeployment)
            container
        }


        // Volumes
        if (deployment.spec.template.spec.volumes) {
            def index = 0
            def volumes = deployment.spec.template.spec.volumes.collect { volume ->
                def retval = [name: volume.name]
                logDeployment.push("/spec/template/spec/volumes[${index}]/name")
                if (volume.hostPath?.path) {
                    retval.hostPath = volume.hostPath.path
                    logDeployment.push("/spec/template/spec/volumes[${index}]/hostPath/path")
                }
                index += 1
                retval
            }
            efService.service.volume = new JsonBuilder(volumes).toString()
        }

        def flatService = flattenMap('', kubeService, [:])
        def flatDeployment = flattenMap('', deployment, [:])

//        def ignoreList = []
        flatService.each{ key, value ->
            if (!listContains(logService, key)){
//                logger WARNING, "Ignored items ${key} = ${value} from Service '${kubeService.metadata.name}'!"
//                ignoreList.push("Ignored items ${key} = ${value} from Service '${kubeService.metadata.name}'!")
                ignoreList.push([type: "Service", name: "${kubeService.metadata.name}", field: "${key} = ${value}"])

            }

        }
        flatDeployment.each{ key, value ->
            if (!listContains(logDeployment, key)){
//                logger WARNING, "Ignored items ${key} = ${value} from Deployment '${deployment.metadata.name}'!"
//                ignoreList.push("Ignored items ${key} = ${value} from Deployment '${deployment.metadata.name}'!")
                ignoreList.push([type: "Deployment", name: "${deployment.metadata.name}", field: "${key} = ${value}"])
            }
        }

        efService
    }

    def listContains (list, key){
        def bool = false
        list.each{item->
            if (item.compareTo(key) == 0){
                bool = true
            }
        }
        bool
    }

//    This one can be redefined for OpenShift
    def buildServiceMapping(kubeService) {
        def mapping = [:]
        mapping.loadBalancerIP = kubeService.spec?.loadBalancerIP
        mapping.serviceType = kubeService.spec?.type
        mapping.sessionAffinity = kubeService.spec?.sessionAffinity
        def sourceRanges = kubeService.spec?.loadBalancerSourceRanges?.join(',')

        mapping.loadBalancerSourceRanges = sourceRanges
        // Not here, is's from kube
        // if (namespace != 'default') {
        //     mapping.namespace = namespace
        // }

        // Routes
        def serviceName = getKubeServiceName(kubeService)

        def route
        // One route per service for us
        // OpenShift allows more than one route
        parsedConfigList.each { object ->
            if (object.kind == KIND_ROUTE && object.spec?.to?.kind == KIND_SERVICE && object.spec?.to?.name == serviceName) {
                if (route) {
                    def routeName = object.metadata?.name
                    logger WARNING, "Only one route per service is allowed in ElectricFlow. The route ${routeName} will not be added."
                }
                else {
                    route = object
                }
            }
        }

        if (route) {
            mapping.routeName = route.metadata?.name
            mapping.routeHostname = route.spec?.host
            mapping.routePath = route.spec?.path
            mapping.routeTargetPort = route.spec?.port?.targetPort
        }

        return mapping
    }

    def updateEFService(efService, kubeService) {
        def payload = kubeService.service
        def projName = efService.projectName
        def serviceName = efService.serviceName
        payload.description = efService.description ?: "Updated by EF Import Microservices"
        def result = updateService(projName, serviceName, payload)
        result
    }

    def createEFService(projectName, service, appName = null) {
        def payload = service.service
        payload.addDeployProcess = true
        payload.description = "Created by EF Import Microservices"
        def result = createService(projectName, payload, appName)
        result
    }

    def equalNames(String oneName, String anotherName) {
        assert oneName
        assert anotherName
        def normalizer = { name ->
            name = name.toLowerCase()
            name = name.replaceAll('-', '.')
        }
        return normalizer(oneName) == normalizer(anotherName)
    }

    def getMaxCapacity(defaultCapacity, maxSurge) {
        assert defaultCapacity
        if (maxSurge > 1) {
            return defaultCapacity + maxSurge
        }
        else {
            return null
        }
    }

    def getMinCapacity(defaultCapacity, maxUnavailable) {
        assert defaultCapacity
        if (maxUnavailable > 1) {
            return defaultCapacity - maxUnavailable
        }
        else {
            return null
        }
    }

    def getMinAvailabilityPercentage(percentage) {
        percentage = percentage.replaceAll('%', '').toInteger()
        return 100 - percentage
    }

    def getMaxRunningPercentage(percentage) {
        percentage = percentage.replaceAll('%', '').toInteger()
        return 100 + percentage
    }

    def parseImage(image) {
        // examples:
        // 'some.domain.name/repository-name:image-tag' = registry / repository name : tag
        // 'localhost:5000/some-namespace/repository-name:image-tag' = registry / repository namespace / repository name : tag
        // fyi... registry cannot contain extra URI path according to Docker limitations

        def parts = image.split('/')

        def optionalRegistry
        def optionalNamespace
        def repository
        def optionalTag

        def repositoryAndOptionalTag = parts.last()

        def repositoryAndOptionalTagSplitted = repositoryAndOptionalTag.split(':')
        repository = repositoryAndOptionalTagSplitted.first()
        if (repositoryAndOptionalTagSplitted.size() > 1) {
            optionalTag = repositoryAndOptionalTagSplitted.last()
        } else {
            optionalTag = 'latest'
        }

        if (parts.size() == 2) {
            if (parts.first() =~ /[:.]/) {
                // if first part contains ':' or '.' then it is considered as registry
                // e.g. 'some.domain.name' from 'some.domain.name/repository-name:image-tag'
                // e.g. 'localhost:5000' from 'localhost:5000/repository-name'
                optionalRegistry = parts.first()
            } else {
                // if first part does not contain ':' or '.' then it is considered as repository namespace
                // e.g. 'username' from 'username/repository-name:image-tag'
                // e.g. 'organization-namespace' from 'organization-namespace/repository-name'
                optionalNamespace = parts.first()
            }
        } else if (parts.size() > 2) {
            // e.g. 'some.domain.name/some-namespace/repository-name:image-tag'
            optionalRegistry = parts.first()
            optionalNamespace = parts[1..parts.size() - 2].join('/')
        }

        def repositoryWithOptionalNamespace
        if (optionalNamespace) {
            repositoryWithOptionalNamespace = optionalNamespace + '/' + repository
        } else {
            repositoryWithOptionalNamespace = repository
        }

        return [imageName: repositoryWithOptionalNamespace, version: optionalTag, repoName: optionalNamespace, registry: optionalRegistry]
    }

    def getImageName(image) {
        parseImage(image).imageName
    }

    def getRepositoryURL(image) {
        parseImage(image).repoName
    }

    def getImageVersion(image) {
        parseImage(image).version
    }

    def getRegistryUri(image) {
        parseImage(image).registry
    }

    def buildContainerDefinition(kubeContainer){//, logDeployment, index) {
        def container = [
            container: [
                containerName: kubeContainer.name,
                imageName: getImageName(kubeContainer.image),
                imageVersion: getImageVersion(kubeContainer.image),
                registryUri: getRegistryUri(kubeContainer.image) ?: null
            ]
        ]
        println '-------->testPoint1'
        container.env = kubeContainer.env?.collect {
            if(it.value) {
                [environmentVariableName: it.name, value: it.value]
            } else if(it.valueFrom) {
                def valueString = new JsonBuilder(it.valueFrom).toString()
                println "VALUESTRING ${valueString}"
                [environmentVariableName: it.name, value: valueString]
            }
        }
        println '<--------testPoint1'

        if (kubeContainer.command) {
            def entryPoint = kubeContainer.command.join(',')
            container.container.entryPoint = entryPoint
        }
        if (kubeContainer.args) {
            def args = kubeContainer.args.join(',')
            container.container.command = args
        }
        // Ports
        if (kubeContainer.ports) {
            container.ports = kubeContainer.ports.collect { port ->
                def name

                if (port.name) {
                    name = port.name
                }
                else {
                    name = "${port.protocol}${port.containerPort}"
                }
                [portName: name.toLowerCase(), containerPort: port.containerPort]
            }
        }

        container.mapping = [:]

        // Liveness probe
        if (kubeContainer.livenessProbe) {
            def processedLivenessFields = []
            def probe = kubeContainer.livenessProbe.httpGet
            processedLivenessFields << 'httpGet'
            container.mapping.with {
                livenessHttpProbePath = probe?.path
                livenessHttpProbePort = probe?.port
                livenessInitialDelay = kubeContainer.livenessProbe?.initialDelaySeconds
                livenessPeriod = kubeContainer.livenessProbe?.periodSeconds
                processedLivenessFields << 'initialDelaySeconds'
                processedLivenessFields << 'periodSeconds'

                def probeHeaderSize = probe?.httpHeaders?.size()
                if (probeHeaderSize && probeHeaderSize > 1) {
                    logger WARNING, 'Only one liveness header is supported, will take the first'
                }
                def header = probe?.httpHeaders?.first()
                livenessHttpProbeHttpHeaderName = header?.name
                livenessHttpProbeHttpHeaderValue = header?.value
            }
            kubeContainer.livenessProbe?.each { k, v ->
                if (!(k in processedLivenessFields) && v) {
                    logger WARNING, "Field ${k} from livenessProbe is not supported"
                }
            }
        }
        // Readiness probe
        if (kubeContainer.readinessProbe) {
            def processedFields = ['command']
            container.mapping.with {
                def command = kubeContainer.readinessProbe.exec?.command
                readinessCommand = command?.first()
                if (command?.size() > 1) {
                    logger WARNING, 'Only one readiness command is supported'
                }
                processedFields << 'initialDelaySeconds'
                processedFields << 'periodSeconds'
                readinessInitialDelay = kubeContainer.readinessProbe?.initialDelaySeconds
                readinessPeriod = kubeContainer.readinessProbe?.periodSeconds
            }

            kubeContainer.readinessProbe?.each { k, v ->
                if (!(k in processedFields) && v) {
                    logger WARNING, "Field ${k} is from readinessProbe not supported"
                }
            }
        }
        def resources = kubeContainer.resources
        container.container.cpuCount = parseCPU(resources?.requests?.cpu)
        container.container.memorySize = parseMemory(resources?.requests?.memory)
        container.container.cpuLimit = parseCPU(resources?.limits?.cpu)
        container.container.memoryLimit = parseMemory(resources?.limits?.memory)

        // Volume mounts
        def mounts = kubeContainer.volumeMounts?.collect { vm ->
            def retval = [name: vm.name]
            if (vm.mountPath) {
                retval.mountPath = vm.mountPath
            }
            retval
        }
        if (mounts) {
            container.container.volumeMount = new JsonBuilder(mounts).toString()
        }

        container
    }

    def isSystemService(service) {
        def name = service.metadata.name
        name == 'kubernetes'
    }

    def parseCPU(cpuString) {
        if (!cpuString) {
            return
        }
        if (cpuString =~ /m/) {
            def miliCpu = cpuString.replace('m', '') as int
            def cpu = miliCpu.toFloat() / 1000
            return cpu
        }
        else {
            return cpuString.toFloat()
        }
    }

    def parseMemory(memoryString) {
        if (!memoryString) {
            return
        }
        if (!(memoryString instanceof String)){
            memoryString = memoryString.toString()
        }
        // E, P, T, G, M, K
        def memoryNumber = memoryString.replaceAll(/\D+/, '')
        def suffix = memoryString.replaceAll(/\d+/, '')
        def power
        ['k', 'm', 'g', 't', 'p', 'e'].eachWithIndex { elem, index ->
            if (suffix =~ /(?i)${elem}/) {;
                power = index - 1
                // We store memory in MB, therefore KB will be power -1, mb will be the power of 1 and so on
            }
        }
        if (power) {
            def retval = memoryNumber.toInteger() * (1024 ** power)
            return Math.ceil(retval).toInteger()
        }
        else {
            return memoryNumber.toInteger()
        }
    }

    def getCredName(cred) {
        "${cred.repoUrl} - ${cred.userName}"
    }

    def prettyPrint(object) {
        println new JsonBuilder(object).toPrettyString()
    }

    def pretty(o) {
        new JsonBuilder(o).toPrettyString()
    }

    def publishReport(ignoreList) {
        def text = renderIgnoredFieldsReport(ignoreList, REPORT_TEMPLATE)

        def dir = new File('artifacts').mkdir()
        def random = new Random()
        def randomSuffix = random.nextInt(10 ** 5)

        def reportFilename = "OpenShift_IgnoreList_${randomSuffix}.html"
        def report = new File("artifacts/${reportFilename}")
        report.write(text)
        String jobStepId = System.getenv('COMMANDER_JOBSTEPID')

        def reportName = "OpenShift Ignored Fields Report"
        publishLink(reportName, "/commander/jobSteps/${jobStepId}/${reportFilename}")
    }

    def renderIgnoredFieldsReport(tableText, String template) {
        def engine = new groovy.text.SimpleTemplateEngine()
        def templateParams = [:]

        templateParams.tableText = tableText
        def text = engine.createTemplate(template).make(templateParams).toString()
        text
    }

    def publishLink(String name, String link) {
        setEFProperty("${REPORT_URL_PROPERTY}${name}", link)
        try {
            setEFProperty("/myJob/report-urls/${name}", link)
        }
        catch (Throwable e) {
            logger ERROR, "Issues while setting property cause ${e} !"
        }
        logger INFO, "Some fields have not been imported. Full list of ignored fields available in the report on the link: ${link} !"
    }

    def buildReportText(list){
        def writer = new StringWriter()
        def markup = new groovy.xml.MarkupBuilder(writer)
        markup.html{
            list.each{ item ->
                tr {
                    td(class:"text-center", item.type)
                    td(class:"text-center", item.name)
                    td(class:"text-center", item.field)
                }
            }
        }
        writer.toString()
    }

    def getKubeServiceName(kubeService) {
        def name = kubeService.metadata?.name
        assert name
        return name
    }

    //------------------------------------------------------------------------------------------------------------------------------------------------------------------
    //  DUKIM-ADDED
    //------------------------------------------------------------------------------------------------------------------------------------------------------------------
    def createServiceMapDetails2(projName, serviceName, envMapName, serviceClusterMapName, payload, appName) {
        def result = doRestPost("/rest/${REST_VERSION}/projects/${projName}/applications/${appName}/tierMaps/${envMapName}/serviceClusterMappings/${serviceClusterMapName}/serviceMapDetails", payload, false)
        result?.data
    }

   def setPropertyToServiceObject(String projectName, String applicationName, String serviceName, String propertyName, String value) { 
        def payload = [:] 
        payload << [
            value: value,
            projectName: projectName,
            applicationName: applicationName,
            serviceName: serviceName
        ]
        payload = JsonOutput.toJson(payload)
 
        doHttpPut("/rest/v1.0/properties/${propertyName}", payload)
    }   

    def saveDeploymentsToServiceObject(String projectName, String applicationName, String serviceName) {
        if(applicationName) { 
            parsedConfigList.each { config ->
                if (config.kind in ['Deployment','DeploymentConfig']){
                    setPropertyToServiceObject(projectName, applicationName, serviceName, config.metadata.name ,new JsonBuilder(config).toPrettyString())
                }
            }
        } 
    }
}
