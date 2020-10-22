@Grab('org.yaml:snakeyaml:1.19')
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.yaml.snakeyaml.Yaml

public class ImportFromTemplateEx extends EFClient  { 
    Yaml parser = new Yaml()
    def parsedList = []
    def resourcesMap = [:].withDefault {[]}
    
    def getResoureMap() {
        resourcesMap
    } 

    def importFromTemplate(fileYAML){
        def efServices = []
        def configList = fileYAML

        def parsedConfig = parser.load(configList)

        parsedConfig.objects.each{ obj ->
            parsedList.push(obj)
        }

        logger INFO, "Parsed template strings:\n${fileYAML}\n"

        def buildConfigs = getParsedResouce("BuildConfig", parsedList)
        if(buildConfigs) {
            resourcesMap['buildconfigs'] = buildConfigs
        }

        def imageStreams = getParsedResouce("ImageStream", parsedList)
        if(imageStreams) { 
            resourcesMap['imagestreams'] = imageStreams
        }

        def persistentVolumes = getParsedResouce("PersistentVolume", parsedList) 
        if(persistentVolumes) { 
            resourcesMap['persistentvolumes'] = persistentVolumes
        }
      
        def persistentVolumeClaims = getParsedResouce("PersistentVolumeClaim", parsedList)
        if(persistentVolumeClaims) { 
            resourcesMap['persistentvolumeclaims'] = persistentVolumeClaims
        }

        def clusterroles = getParsedResouce("ClusterRole", parsedList)
        if(clusterroles) { 
            resourcesMap['clusterroles'] = clusterroles
        } 

        def clusterrolebindings = getParsedResouce("ClusterRoleBinding", parsedList)
        if(clusterrolebindings) { 
            resourcesMap['clusterrolebindings'] = clusterrolebindings
        }
  
        def clusterNetworks = getParsedResouce("ClusterNetwork", parsedList)
        if(persistentVolumeClaims) { 
            resourcesMap['clusternetworks'] = clusterNetworks
        }

        def egressnetworkpolicies = getParsedResouce("EgressNetworkPolicy", parsedList)
        if(egressnetworkpolicies) { 
            resourcesMap['egressnetworkpolicies'] = egressnetworkpolicies
        }

        def clusterresourcequotas = getParsedResouce("ClusterResourceQuota", parsedList)
        if(clusterresourcequotas) { 
            resourcesMap['clusterresourcequotas'] = clusterresourcequotas
        }

        def clusterresourcequotas = getParsedResouce("Secret", parsedList)
        if(clusterresourcequotas) { 
            resourcesMap['secrets'] = clusterresourcequotas
        }

        def clusterresourcequotas = getParsedResouce("ConfigMap", parsedList)
        if(clusterresourcequotas) { 
            resourcesMap['configmaps'] = clusterresourcequotas
        }

        return resourcesMap
    }

    def resolveTemplateByParameters(template,templateValues) {
        def parametersMap = [:]
        if (templateValues) {
            templateValues = templateValues.trim()
            def values = templateValues.split(/\s*,\s*/)
            values.each { parameterAndValue ->
                if (parameterAndValue.contains('=')) {
                    String[] parameterAndValueSplitted = parameterAndValue.split(/\s*=\s*/)
                    parametersMap.put(parameterAndValueSplitted[0].trim(), parameterAndValueSplitted[1].trim())
                }
            }
        }
 
        def parametersMapCloned = parametersMap.clone();
       
        Yaml parser = new Yaml()
        def parsedTemplate = parser.load(template)
        def parametersWithDefaultValuesFromTemplate = [:]
        parsedTemplate.parameters.each { item ->
            if (item.containsKey('value')) {
                parametersWithDefaultValuesFromTemplate.put(item.name, item.value)
            }

        }
        
        if(parametersWithDefaultValuesFromTemplate) {
            parametersWithDefaultValuesFromTemplate.each { parameterName, parameterValue ->
                if (!parametersMapCloned.containsKey(parameterName)) {
                    logger INFO, "Parameter '$parameterName' is not provided, using its default value from template: '$parameterValue'"
                    parametersMapCloned.put(parameterName, parameterValue)
                }
            }
        }
        
        def resolvedTemplate = template
        if(parametersMapCloned) {
            parametersMapCloned.each { parameterName, parameterValue ->
                resolvedTemplate = resolvedTemplate.replaceAll(/\$\{{1,2}\s*${parameterName}\s*\}{1,2}/, parameterValue)
            }
        }

        return resolvedTemplate
    }

    def getParsedResouce(String typeName, def parsedList){
        def resources = []
        if(parsedList) {
            parsedList.each { res ->
                if (res.kind == typeName) {
                    if (!isSystemDefinition(res)) {
                        resources.push(res)
                    }
                }
            }
        }
        resources
    }

    def isSystemDefinition(resource) {
        def name = resource.metadata.name
        name == 'kubernetes'
    }

}
