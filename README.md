# slack-gcal

Slack integration with Google calendar.

### Setup

 * Set up [ngrok](https://ngrok.com/download) if you need to tunnel to your localhost from Slack:
 <pre>
 ./ngrok http 8080 
 </pre>

 * Create a new Google Developer App and create a client-secrets.json file:<br/>
 [https://console.developers.google.com/project/slack-gcal/apiui/credential/oauthclient](https://console.developers.google.com/project/slack-gcal/apiui/credential/oauthclient)
 	* Application type: ``Web application``
 	* Name: ``Web App``
 	* Authorized redirect URIs:<br/>
 	``http://localhost:8080/oauthcallback``<br/>
 	``https://<something>.ngrok.io/oauthcallback``<br/>
 	* Put this .json file in `src/main/resources/slack-gcal-test-browser-client-secrets.json`.
 	
 * Change `val serverRootUrl` in `SlackCalendarService.scala` to the ngrok host above.
 
 * To run this:
 <pre>
 sbt
 ~re-start
 </pre>
 * Create a new Slack Slash Command.<br/>
[https://meetuphq.slack.com/services/new/slash-commands](https://meetuphq.slack.com/services/new/slash-commands) 
	* Command: ``/findtimes``
	* URL: ``(ngrok above)``
	* Method: ``POST``
	* Description: ``Finds free time on Gcal with @users as attendees.``
	* Usage hint: ``@user[,@user,...] [YYYY-MM-dd'T'HH:mm:ssZ]``

### Todos

* Make this use a Service Account rather than do this OAuth2 nonsense across Slack.<br/>
[https://developers.google.com/identity/protocols/OAuth2ServiceAccount](https://developers.google.com/identity/protocols/OAuth2ServiceAccount)