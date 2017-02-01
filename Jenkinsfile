#!/usr/bin/env groovy
import groovy.json.JsonSlurperClassic
node ('general') {
	try {
		stage("Checkout docker-aurora-cheduler ") {
			sh "git clone git@github.medallia.com:medallia/docker-aurora-scheduler.git"
			println "Checked out docker-aurora-scheduler"
			sh "cd docker-aurora-scheduler"
			sh "pwd"
		}
		
		stage("build-artifacts") {
			println "AURORA_RELEASE=$gitTag make build-artifacts"
		}
	
		stage("build-image") {
			println "AURORA_RELEASE=$gitTag make build-image"
		}
	
		stage("publish-image") {
			println "AURORA_RELEASE=$gitTag make publish-image"
		}

		// Cleans up the local docker image
		stage("Cleanup") {
			println "clean-up"
		}


	} catch (err) {
		currentBuild.result = 'FAILURE'
		throw err
	} finally {
		// Success or failure, always send notifications
		notifyBuild(currentBuild.result)
	}
}

def notifyBuild(String buildStatus = 'STARTED') {
		// build status of null means successful
		buildStatus =  buildStatus ?: 'SUCCESSFUL'

		// Default values
		def subject = "${buildStatus}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
		def details = """<p>'${buildStatus}': Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':</p>
		<p>Check console output at '<a href='${env.BUILD_URL}'>${env.JOB_NAME} [${env.BUILD_NUMBER}]</a>'</p>"""

		// Send notifications
		emailext (
		subject: subject,
		body: details,
		recipientProviders: [[$class: 'DevelopersRecipientProvider']],
		attachLog: true
		)
}
