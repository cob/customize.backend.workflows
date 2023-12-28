import groovy.transform.Field
import com.google.common.cache.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit


@Field DEFS_TO_IGNORE = [
        "Work Queues"
        , "Work Item"
];


@Field static workQueuesCache = CacheBuilder.newBuilder()
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build();

if (msg.product == "recordm" && msg.type == "Work Queues") {
    log.info("Invalidating WorkQueues cache")
    workQueuesCache.invalidateAll()
}


if (msg.product == "recordm" && !DEFS_TO_IGNORE.contains(msg.type)) {

    def query = "specific_data.raw:\"${msg.type}\" AND launch_condition:*"
    def wqHits = getWorkQueueshits(query)

    wqHits.each { hit ->
        def launchCondition = hit.value('Launch Condition')
        log.debug("Launch content: ${launchCondition.split("\n")}")

        try {
            if (evaluate(launchCondition)) {
                def possibleStates = hit.value('Possible States')
                log.info("Launch condition true: ${launchCondition.split("\n")}")
                log.info("Possible States: ${possibleStates}")

                Map updates = [:]
                updates["Customer Data"] = msg.instance.id
                updates["Work Queue"] = hit.value("id")
                updates["State"] = possibleStates[0]

                log.info("updates: ${updates}")
                recordm.create("Work Item", updates)
            }
        } catch (Exception e) {
            log.error("couldn't evaluate launchcondition {{" +
                    "WQ: ${hit.value('id')}:${hit.value('Code')}:${hit.value('Name')}, " +
                    "condition: ${launchCondition}" +
                    "}}", e);
        }
    }
}

//Assumes max 200 WQs per query
def getWorkQueueshits(query) {
    try {
        return workQueuesCache.get(
                query,
                { recordm.search("Work Queues", query, [size: 200]).getHits() }
        )
    } catch (ExecutionException ignore) {
        return [];
    }
}