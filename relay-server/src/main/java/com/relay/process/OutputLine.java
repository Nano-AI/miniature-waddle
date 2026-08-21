package com.relay.process;

public record OutputLine(long seq, String stream, String text, long timestamp) {
}
