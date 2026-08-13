package com.squeezeit;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

/**
 * Entry point for the SqueezeIt desktop application.
 * Bootstraps JavaFX and loads the primary FXML view.
 */
public class MainApp extends Application {

    @Override
    public void start(Stage primaryStage) throws IOException {
        FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/com/squeezeit/main-view.fxml"));
        Scene scene = new Scene(loader.load(), 980, 740);

        // Load dark-mode stylesheet
        scene.getStylesheets().add(
                Objects.requireNonNull(
                        getClass().getResource("/com/squeezeit/styles.css")
                ).toExternalForm());

        try {
            primaryStage.getIcons().add(
                    new Image(Objects.requireNonNull(
                            getClass().getResourceAsStream("/com/squeezeit/icon.png"))));
        } catch (Exception ignored) {
            // Icon is optional – skip gracefully
        }

        primaryStage.setTitle("SqueezeIt v1.2 – Local File Compressor");
        primaryStage.setMinWidth(820);
        primaryStage.setMinHeight(600);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
