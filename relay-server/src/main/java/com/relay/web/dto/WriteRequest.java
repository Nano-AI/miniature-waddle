package com.relay.web.dto;

public class WriteRequest {
    public String path;
    public String content;
    public String encoding;
    public String mode;
    public Boolean makedirs = Boolean.FALSE;
}
