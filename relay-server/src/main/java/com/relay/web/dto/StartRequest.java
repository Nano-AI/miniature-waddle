package com.relay.web.dto;

import java.util.List;
import java.util.Map;

public class StartRequest {
    public List<String> args;
    public String command;
    public String cwd;
    public Map<String, String> env;
}
