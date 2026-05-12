package com.github.ttarasiewicz.srforgeassistant.probe;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Written in Java so the compiler does not emit synthetic forwarder methods
 * for {@link ToolWindowFactory}'s {@code @ApiStatus.Internal} default methods
 * (getAnchor, getIcon, manage, isDoNotActivateOnStart). Kotlin would generate
 * those forwarders, which the Plugin Verifier then reports as overrides of
 * internal API.
 */
public class ProbeToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ProbeToolWindowPanel panel = new ProbeToolWindowPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return true;
    }
}
