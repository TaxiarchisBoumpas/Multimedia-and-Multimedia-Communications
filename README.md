# 🎥 Multimedia Streaming System – Πολυμέσα και Πολυμεσικές Επικοινωνίες

This project implements a **Video Streaming System** using **Java sockets** (TCP & UDP) as part of the *“Multimedia and Multimedia Communications”* university course.

It demonstrates how client–server communication can be used to transmit multimedia content in real time, using adaptive streaming logic based on connection speed and format selection.

---

## 🧠 Project Overview

The system consists of two main components:

### 🖥️ Streaming Server
- Manages available multimedia files (video/audio)
- Listens for client connections via TCP sockets
- Provides metadata about supported formats and bitrates
- Transmits selected content over TCP or UDP (depending on client request)

### 💻 Streaming Client
- Connects to the server via TCP
- Performs a **speed test** to determine optimal streaming quality
- Requests the list of available media files and formats
- Selects desired content and receives the stream
- Uses **FFmpeg** for playback or format conversion

---

## ⚙️ Technologies Used
- **Java** (Networking – TCP & UDP sockets)
- **FFmpeg** (streaming and transcoding)
- **Multithreading** for handling multiple clients
- **OOP design** for modular client-server structure

---

## 🚀 How to Run

### 1️⃣ Compile
```bash
javac StreamingServer.java
javac StreamingClient.java

2️⃣ Run the Server
java StreamingServer

3️⃣ Run the Client
java StreamingClient

