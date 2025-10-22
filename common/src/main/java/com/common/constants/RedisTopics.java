package com.common.constants;

public class RedisTopics {

    // Stream or channel names used for job queue
    public static final String JOB_STREAM = "job_stream";
    public static final String JOB_CHANNEL = "job_channel";

    private RedisTopics() {
        // Prevent instantiation
    }
}

