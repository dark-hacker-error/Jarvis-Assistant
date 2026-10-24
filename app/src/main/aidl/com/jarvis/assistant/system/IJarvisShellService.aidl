package com.jarvis.assistant.system;

interface IJarvisShellService {
    String runCommand(String command, int timeoutMs) = 1;
    void destroy() = 16777114;
}
