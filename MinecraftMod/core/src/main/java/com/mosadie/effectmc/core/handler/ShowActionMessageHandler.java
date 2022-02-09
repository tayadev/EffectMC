package com.mosadie.effectmc.core.handler;

import com.mosadie.effectmc.core.EffectMCCore;

public class ShowActionMessageHandler extends EffectRequestHandler {


    public ShowActionMessageHandler(EffectMCCore core) {
        super(core);
        addStringProperty("message", "", true, "Message", "Hello World!");
    }

    @Override
    public String getEffectName() {
        return "Show Action Message";
    }

    @Override
    public String getEffectTooltip() {
        return "Show a message on the action bar.";
    }

    @Override
    String execute() {
        core.getExecutor().log("Showing action bar message: " + getProperty("message").getAsString());
        if (core.getExecutor().showActionMessage(getProperty("message").getAsString()))
            return "Showing action bar message: " + getProperty("message").getAsString();
        else
            return "Failed to show action bar message.";
    }
}
