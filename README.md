# Cameo HTTP Server Plugin

A lightweight HTTP server integrated as a plugin within CATIA Magic / Cameo Systems Modeler. This project provides a bridge between external HTTP requests and the Cameo JVM, allowing for automated model manipulation and introspection.

## Capabilities

### Current Version (v1.0.0)
- **Request Logging**: All incoming HTTP requests are captured and logged directly to the Cameo notification window (GUI Log).
- **Lightweight Footprint**: Built using the standard JDK HTTP server to minimize external dependencies and overhead.
- **Configurable Port**: The server port can be adjusted via the system property `cameo.http.server.port` (defaults to `18741`).

### Planned Capabilities
- **Dynamic Endpoint Handling**: Ability to define request handlers in Groovy scripts.
- **Hot Reloading**: Dynamic loading/reloading of Groovy scripts without requiring a Cameo restart.
- **Cameo API Integration**: Providing a streamlined interface for Groovy scripts to interact with the Cameo OpenAPI and EMF model.

## Internal Architecture

### Component Overview
The plugin follows a layered architecture to decouple the Cameo plugin lifecycle from the HTTP server logic:

1.  **Plugin Layer (`CameoHttpServerPlugin`)**:
    - Extends the Cameo `Plugin` class.
    - Manages the lifecycle of the server (`init()` and `close()`).
    - Handles initial configuration and error reporting to the GUI.

2.  **Server Layer (`CameoHttpServer`)**:
    - Wraps `com.sun.net.httpserver.HttpServer`.
    - Manages the server instance, thread pool (FixedThreadPool), and route registration.
    - Acts as the central dispatcher for incoming requests.

3.  **Handler Layer (`HttpHandler` implementations)**:
    - Implements the `com.sun.net.httpserver.HttpHandler` interface.
    - Contains the business logic for specific endpoints.
    - Currently includes `LogRequestHandler` for basic logging.

### Data Flow
`HTTP Request` $\rightarrow$ `HttpServer` $\rightarrow$ `HttpHandler` $\rightarrow$ `Cameo GUI Log`

## Main Functions & Interfaces

### Core Classes
- `CameoHttpServerPlugin`: The entry point for Cameo. Responsible for bootstrapping the server.
- `CameoHttpServer`: The engine that maintains the HTTP listener and maps URIs to handlers.
- `LogRequestHandler`: A specific implementation of `HttpHandler` that transforms an `HttpExchange` into a GUI log message.

### Key Interfaces
- `com.sun.net.httpserver.HttpHandler`: The primary interface used to define endpoint behavior.
- `com.nomagic.magicdraw.plugins.Plugin`: The interface required for integration with the Cameo plugin architecture.

## Architecture Decisions

### 1. Use of `com.sun.net.httpserver.HttpServer`
**Decision**: Utilize the built-in JDK HTTP server instead of a heavyweight framework like Spring Boot or Jetty.
**Reasoning**: 
- **Minimal Dependencies**: Reduces the risk of classpath conflicts within the complex Cameo JVM environment.
- **Performance**: Sufficient for the intended low-latency, lightweight signaling and model manipulation tasks.
- **Simplicity**: Eases deployment and build process.

### 2. Dynamic Groovy Endpoints (Planned)
**Decision**: Implement a mechanism to load endpoint logic from external `.groovy` files.
**Reasoning**: 
- **Fast Iteration**: Developing against the Cameo API typically requires frequent restarts of a large application. Groovy allows for "hot-swapping" logic.
- **Stability**: Keeps the Java core stable and minimal, while pushing volatile business logic to scripts.

### 3. Port Selection
**Decision**: Default port set to `18741`.
**Reasoning**: To avoid conflict with other common Cameo plugins, specifically the Cameo MCP Bridge which defaults to `18740`.

## Build and Deployment
The project uses Gradle for building.

### Development Environment
To develop and build this plugin, the following tools are required:
- **OpenJDK 21**: The project is built for and targets Java 21.
- **Gradle**: Used for dependency management and plugin assembly.

On Alpine Linux, these can be installed via:
`apk add openjdk21 gradle`

- **Build**: `./gradlew assemblePlugin`
- **Deploy**: `./gradlew deploy -PcameoHome="/path/to/Cameo"`

## License
This project is licensed under the Apache License 2.0.

Copyright © Alexander Haarer
