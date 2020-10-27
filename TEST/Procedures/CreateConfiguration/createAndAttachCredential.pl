#
#  Copyright 2016 Electric Cloud, Inc.
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#

##########################
# createAndAttachCredential.pl
##########################
use ElectricCommander;

use constant {
	SUCCESS => 0,
	ERROR   => 1,
};

## get an EC object
my $ec = new ElectricCommander();
$ec->abortOnError(0);

my $credName = '$[config]';

my $xpath = $ec->getFullCredential($credName);
my $errors = $ec->checkAllErrors($xpath);
my $clientID = $xpath->findvalue("//userName");
my $clientSecret = $xpath->findvalue("//password");

my $projName = '$[/myProject/projectName]';

# Create credential
$ec->deleteCredential($projName, $credName);
$xpath = $ec->createCredential($projName, $credName, $clientID, $clientSecret);
$errors .= $ec->checkAllErrors($xpath);

# Give config the credential's real name
my $configPath = "/projects/$projName/ec_plugin_cfgs/$credName";
$xpath = $ec->setProperty($configPath . "/credential", $credName);
$errors .= $ec->checkAllErrors($xpath);

# Give job launcher full permissions on the credential
my $user = '$[/myJob/launchedByUser]';

$xpath = $ec->getAclEntry("user", $user, {projectName => $projName, credentialName => $credName});
if (!$xpath->findvalue('//aclEntryId')) {
    $xpath = $ec->createAclEntry("user", $user,
        { projectName                  => $projName,
            credentialName             => $credName,
            readPrivilege              => allow,
            modifyPrivilege            => allow,
            executePrivilege           => allow,
            changePermissionsPrivilege => allow });
    $errors .= $ec->checkAllErrors($xpath);
}

# Attach credential to steps that will need it
$xpath = $ec->attachCredential($projName, $credName,
    {procedureName => "Check Cluster",
     stepName => "checkCluster"});
$errors .= $ec->checkAllErrors($xpath);

$xpath = $ec->attachCredential($projName, $credName,
    {procedureName => "Provision Cluster on ESX",
     stepName => "provisionCluster"});
$errors .= $ec->checkAllErrors($xpath);

$xpath = $ec->attachCredential($projName, $credName,
    {procedureName => "Deploy Service",
     stepName => "createOrUpdateDeployment"});
$errors .= $ec->checkAllErrors($xpath);

$xpath = $ec->attachCredential($projName, $credName,
    {procedureName => "Undeploy Service",
     stepName => "undeployService"});
$errors .= $ec->checkAllErrors($xpath);

$xpath = $ec->attachCredential($projName, $credName,
    {procedureName => "Cleanup Cluster - Experimental",
     stepName => "cleanup"});
$errors .= $ec->checkAllErrors($xpath);

$xpath = $ec->attachCredential($projName, $credName,
    {procedureName => "Discover",
     stepName => "discover"});
$errors .= $ec->checkAllErrors($xpath);

$xpath = $ec->attachCredential($projName, $credName,
    {procedureName => "Deploy Static Resources",
     stepName => "import"});
$errors .= $ec->checkAllErrors($xpath);

$xpath = $ec->attachCredential($projName, $credName,
    {procedureName => "Start Build",
     stepName => "build"});
$errors .= $ec->checkAllErrors($xpath);

if ("$errors" ne "") {
    # Cleanup the partially created configuration we just created
    $ec->deleteProperty($configPath);
    $ec->deleteCredential($projName, $credName);
    my $errMsg = "Error creating configuration credential: " . $errors;
    $ec->setProperty("/myJob/configError", $errMsg);
    print $errMsg;
    exit 1;
}