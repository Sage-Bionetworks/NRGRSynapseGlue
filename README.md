# NRGRSynapseGlue

This is the integration of NRGR and Synapse, linking data access approval in NRGR to data access in Synapse.

The codebase is used in two ways:

As a cron job, run in Travis CI.  Each day it checks for new applicants, sending out cryptographically signed tokens.  It also checks for incoming email messages from the approving authority.  After validating the tokens included in the email it approves the corresponding applicant(s) and sends out notifications.  The executable is built as a Docker container in DockerHub.  (See Dockerfile for details.)  The .travis.yml file describes the daily cron job which uses the container built in DockerHub.


As a web service, in AWS API Gateway.  A Synapse user can submit a text file containing one or more tokens.  The request is authorized with a Synapse session or access token.  The service will validate any tokens included in the file, approve the corresponding applicants and send out notifications.  The response is a message listing the discovered tokens and whether they were accepted or deemed invalid.  The executable is built as a .jar file by GitHub actions and pushed to an AWS Lambda function where it is made available through the AWS API Gateway.
