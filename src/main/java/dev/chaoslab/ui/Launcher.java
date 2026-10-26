package dev.chaoslab.ui;

import javafx.application.Application;

/**
 * Entry point. It exists only because a JavaFX {@link Application} subclass cannot be started directly as the main
 * class when JavaFX is on the class path rather than the module path ("JavaFX runtime components are missing").
 * Starting through an ordinary class avoids that check.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(ChaosLabApp.class, args);
    }
}
