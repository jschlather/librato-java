package com.librato.metrics;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * User: mihasya
 * Date: 6/14/12
 * Time: 1:51 PM
 * A class that represents an aggregation of metric data from a given run
 */
public class LibratoBatch {
    public static final int DEFAULT_BATCH_SIZE = 500;

    private static final ObjectMapper mapper = new ObjectMapper();

    private final List<Measurement> measurements = new ArrayList<Measurement>();

    private final int postBatchSize;
    private final long timeout;
    private final TimeUnit timeoutUnit;

    private static final Logger LOG = LoggerFactory.getLogger(LibratoBatch.class);

    public LibratoBatch(int postBatchSize, long timeout, TimeUnit timeoutUnit) {
        this.postBatchSize = postBatchSize;
        this.timeout = timeout;
        this.timeoutUnit = timeoutUnit;
    }

    /**
     * for advanced measurement fu
     */
    public void addMeasurement(Measurement measurement) {
        measurements.add(measurement);
    }

    public void addCounterMeasurement(String name, Long value) {
        measurements.add(new CounterMeasurement(name, value));
    }

    public void addGaugeMeasurement(String name, Number value) {
        measurements.add(new SingleValueGaugeMeasurement(name, value));
    }

    public void post(AsyncHttpClient.BoundRequestBuilder builder, String source, long epoch) {
        Map<String, Object> resultJson = new HashMap<String, Object>();
        resultJson.put("source", source);
        resultJson.put("measure_time", epoch);
        List<Map<String, Object>> gaugeData = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> counterData = new ArrayList<Map<String, Object>>();

        int counter = 0;

        Iterator<Measurement> measurementIterator = measurements.iterator();
        while (measurementIterator.hasNext()) {
            Measurement measurement = measurementIterator.next();
            Map<String, Object> data = new HashMap<String, Object>();
            data.put("name", measurement.getName());
            data.putAll(measurement.toMap());
            if (measurement instanceof CounterMeasurement) {
                counterData.add(data);
            } else {
                gaugeData.add(data);
            }
            counter++;
            if (counter % postBatchSize == 0 || !measurementIterator.hasNext()) {
                resultJson.put("counters", counterData);
                resultJson.put("gauges", gaugeData);
                postPortion(builder , resultJson);
                resultJson.remove("gauges");
                resultJson.remove("counters");
                gaugeData = new ArrayList<Map<String, Object>>();
                counterData = new ArrayList<Map<String, Object>>();
            }
        }
        LOG.debug("Posted %d measurements", counter);
    }

    private void postPortion(AsyncHttpClient.BoundRequestBuilder builder, Map<String, Object> chunk) {
        try {
            String chunkStr = mapper.writeValueAsString(chunk);
            builder.setBody(chunkStr);
            Future<Response> response = builder.execute();
            Response result = response.get(timeout, timeoutUnit);
            System.out.println(String.format("Got a %d from api", result.getStatusCode()));
            if (result.getStatusCode() < 200 || result.getStatusCode() >= 300) {
                LOG.error("Received an error from Librato API. Code : %d, Message: %s", result.getStatusCode(), result.getResponseBody());
            }
        } catch (Exception e) {
            LOG.error("Unable to post to Librato API", e);
        }
    }
}