package com.example.farm.protocol;

/** Private, freshly resolved write route; firmware names never come from Cloud parameters. */
public record RuntimeControlChannel(String action, String id, String toolId, Integer driveIndex) { }
