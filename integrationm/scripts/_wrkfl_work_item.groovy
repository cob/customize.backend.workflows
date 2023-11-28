import org.codehaus.jettison.json.JSONObject

import java.math.RoundingMode

if (msg.product == "recordm" && msg.type == "Work Item") {

    if (msg.action == "add") {
        def workQueue = recordm.get(msg.value("Work Queue")).getBody()
        switch (workQueue.value("Agent Type")) {
            case "RPA":
                def script = recordm.get(workQueue.value("RPA Action")).getBody().value("Script")
                log.info("The new workm item is a RPA Action {{action: ${script} }}")

                def actionResponse = actionPacks.imRest.post("/concurrent/${script}", [id: msg.value("Customer Data")], "cob-bot")
                // TODO Era bom se podessemos usar o ReusableResponse
                if (new JSONObject(actionResponse).getString("success") == "true") {
                    recordm.update("Work Item", msg.id, ["State": "Done"], "cob-bot")
                }

                return
            case "AI":
                log.info("TO BE DONE")

                return
            default:
                log.info("New human workitem created {{taskId: ${msg.id} }}")
        }

    } else if (msg.field('State').changed()) {
        def state = msg.value('State')

        def wqSearch = recordm.search("Work Queues", "id.raw:${msg.value('Work Queue')}", [size: 1]);

        //Run the relevant On XXX code pieces aonfigured on the WorkQueue (which make updates on the customer Data instance
        if (wqSearch.success() && wqSearch.getTotal() > 0) {
            def wq = wqSearch.getHits().get(0)

            def code = wq.value("On " + state)
            if (code != null) {
                log.debug("On ${state} CODE: " + code)

                def defName = wq.value("Specific Data")
                def cdQquery = "id.raw:${msg.value('Customer Data')}"
                def cdSearch = recordm.search(defName, cdQquery, [size: 1]);

                if (cdSearch.success() && cdSearch.getTotal() > 0) {
                    Map updates = [:]
                    def data = cdSearch.getHits().get(0)

                    def binding = new Binding(data: data, updates: updates, recordm: recordm)

                    try {
                        new GroovyShell(binding).evaluate(code)

                    } catch (Exception e) {
                        log.error("Error evaluating code {{ 'On ${state}' code: ${code} }}", e)
                        def previousErrors = (msg.value("Automation Errors") ? msg.value("Automation Errors") + "\n\n" : "")
                        recordm.update("Work Item", msg.instance.id, [
                                "State"            : "Error",
                                "Automation Errors": previousErrors +
                                        "Error evaluating 'On ${state}' code: ${code} \n" +
                                        "Error: " + e.getMessage()])
                        return
                    }

                    def rmResponse = recordm.update(defName, cdQquery, updates)

                    if (!rmResponse.ok()) {
                        log.error(rmResponse.getBody(String.class))
                    }
                }
            }
        }


        //Update Workitem dates and times
        Map wiUpdates = [:]
        def nowDateTime = msg._timestamp_;
        def oldState = msg.oldInstance.value('State')

        def dateCreation = msg.value("Date of Creation", Long.class)
        def dateStart = msg.value("Date of Start", Long.class)
        def dateFirstPending = msg.value("Date of first Pending", Long.class)
        def datePending = msg.value("Date of Pending", Long.class)
        def totalTimePendingHours = msg.value("Time of Pending", Double.class) ?: 0

        def isUnassigned = (msg.value('User') == null)
        if (isUnassigned && state != "To Assign") {
            def currentUser = userm.getUser(msg.user).getBody()
            wiUpdates["User"] = currentUser._links.self
            wiUpdates["Date of Assignment"] = nowDateTime
            wiUpdates["Time of Assignment"] = 0.01
        }

        //Casos em que estou a entrar no estado:
        switch (state) {
            case "Pending":
                if (!dateFirstPending) wiUpdates["Date of first Pending"] = nowDateTime
                wiUpdates["Date of Pending"] = nowDateTime
                break;
            case "Canceled":
                wiUpdates["Date of Canceling"] = nowDateTime
                break;
            case "Done":
                wiUpdates["Date of Done"] = nowDateTime
                wiUpdates["Time of Execution"] = dateStart ? getDiifHOurs(dateStart, nowDateTime) : 0.01
                wiUpdates["Time Overall"] = getDiifHOurs(dateCreation, nowDateTime)
                break;
        }

        //Casos em que estou a sair do estado:
        switch (oldState) {
            case "To Assign":
                wiUpdates["Date of Assignment"] = nowDateTime
                wiUpdates["Time of Assignment"] = getDiifHOurs(dateCreation, nowDateTime)
                break;
            case "To Do":
                //se vier de pendente já tenho esta data preenchida
                if (!dateStart) {
                    wiUpdates["Date of Start"] = nowDateTime
                    wiUpdates["Time of Start"] = getDiifHOurs(dateCreation, nowDateTime)
                }
                break;
            case "Pending":
                wiUpdates["Time of Pending"] = totalTimePendingHours + getDiifHOurs(datePending, nowDateTime)
                break;
        }

        def wiUpdateResponse = recordm.update(msg.type, msg.instance.id, wiUpdates)

        if (!wiUpdateResponse.ok()) {
            log.error(wiUpdateResponse.getBody(String.class))
        }
    }
}

static def getDiifHOurs(startTime, endTime) {
    def elapsed = (new BigDecimal(endTime) - new BigDecimal(startTime))
    return elapsed.divide(new BigDecimal(60 * 60 * 1000), 2, RoundingMode.HALF_UP)
}