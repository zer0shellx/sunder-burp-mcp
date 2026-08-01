package net.portswigger

object TestUtils {
    /**
     * Sets the connection state of an SseClient for testing purposes.
     * 
     * Uses reflection to directly modify the internal state of the client.
     * This is useful for testing state transition handling without relying on
     * actual network conditions.
     * 
     * @param client The SseClient instance to modify
     * @param state The ConnectionState to set
     */
    fun setConnectionState(client: SseClient, state: ConnectionState) {
        val stateField = client.javaClass.getDeclaredField("connectionStateMutableStateFlow")
        stateField.isAccessible = true
        val stateFlow = stateField.get(client)
        
        val valueMethod = stateFlow.javaClass.getDeclaredMethod("setValue", Any::class.java)
        valueMethod.isAccessible = true
        valueMethod.invoke(stateFlow, state)
    }
}