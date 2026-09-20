package com.catkiss.senlive2dcompanion;

/**
 * Small host-facing control surface for the Sen portrait layer.
 *
 * <p>The AI companion can keep its dialogue/personality decisions outside this module and map
 * them to these stable semantic entry points. Values and animation curves remain owned by the
 * Sen implementation, so the host does not need to know Cubism parameter IDs.</p>
 */
public interface SenCompanionController {
    /** Selects one of the semantic emotion IDs exposed by {@link SenPerformanceCatalog}. */
    void setEmotion(String emotionId);

    /** Plays one of the program action IDs exposed by {@link SenPerformanceCatalog}. */
    void playAction(String actionId);

    /** Supplies a normalized 0..1 speech envelope; 0 closes the audio-driven mouth layer. */
    void setSpeechAmplitude(float amplitude);

    /** Supplies a normalized OpenGL-stage look target in the -1..1 range. */
    void setLookTarget(boolean active, float normalizedX, float normalizedY);

    /** Enables or disables Sen's authored autonomous idle behavior. */
    void setAutoIdle(boolean enabled);

    /** Shows or hides the portrait layer without destroying its loaded model. */
    void setVisible(boolean visible);

    /** Permanently releases this instance. Create a new view before loading Sen again. */
    void release();
}
