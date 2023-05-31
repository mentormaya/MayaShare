package com.example.mayashare

import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity() {
    private val bufferSize = 4096
    private val port = 5000
    private val separator = "<SEPARATOR>"
    private val discoveryPort = 5001

    private lateinit var editAddress: EditText

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editAddress = findViewById(R.id.edit_address)

        val buttonSend: Button = findViewById(R.id.button_send)
        buttonSend.setOnClickListener { sendFile() }

        val buttonReceive: Button = findViewById(R.id.button_receive)
        buttonReceive.setOnClickListener { receiveFile() }

        val buttonDiscover: Button = findViewById(R.id.button_discover)
        buttonDiscover.setOnClickListener { startPeerDiscovery() }
    }


    private lateinit var fileSelectionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize file selection launcher
        fileSelectionLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                val selectedFilePath = getPath(uri) ?: return@registerForActivityResult
                val file = File(selectedFilePath)
                val fileSize = file.length()
                val fileName = file.name

                val address = editAddress.text.toString()

                Thread {
                    var socket: Socket? = null
                    var outputStream: DataOutputStream? = null
                    var fileInputStream: FileInputStream? = null

                    try {
                        socket = Socket(address, port)
                        outputStream = DataOutputStream(socket.getOutputStream())

                        val fileInfo = "SEND$separator$fileName$separator$fileSize"
                        outputStream.write(fileInfo.toByteArray(StandardCharsets.UTF_8))

                        fileInputStream = FileInputStream(file)

                        val buffer = ByteArray(bufferSize)
                        var bytesRead: Int

                        while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                        }

                        outputStream.flush()
                        socket.shutdownOutput()

                        val response = socket.getInputStream().bufferedReader().readLine()

                        runOnUiThread {
                            Toast.makeText(this, response, Toast.LENGTH_SHORT).show()
                        }

                    } catch (e: Exception) {
                        e.printStackTrace()
                        runOnUiThread {
                            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        outputStream?.close()
                        fileInputStream?.close()
                        socket?.close()
                    }
                }.start()
            }
        }

        // Rest of the code
        // ...
    }

    private fun sendFile() {
        fileSelectionLauncher.launch("*/*")
    }

    private fun receiveFile() {
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val savePath = storageDir?.absolutePath

        if (savePath != null) {
            Thread {
                val serverSocket = ServerSocket(port)

                runOnUiThread {
                    Toast.makeText(this, "Waiting for file...", Toast.LENGTH_SHORT).show()
                }

                var socket: Socket? = null
                var fileOutputStream: FileOutputStream? = null

                try {
                    socket = serverSocket.accept()

                    val dataInputStream = socket.getInputStream()
                    val receivedData = dataInputStream.bufferedReader().readLine()

                    val parts = receivedData.split(separator)
                    val command = parts[0]
                    val fileName = parts[1]
                    val fileSize = parts[2].toLong()

                    if (command == "SEND") {
                        val filePath = "$savePath/$fileName"
                        fileOutputStream = FileOutputStream(filePath)

                        val buffer = ByteArray(bufferSize)
                        var bytesRead: Int
                        var progress: Long = 0

                        while (progress < fileSize) {
                            bytesRead = dataInputStream.read(buffer)
                            if (bytesRead == -1) break
                            fileOutputStream.write(buffer, 0, bytesRead)
                            progress += bytesRead
                        }

                        runOnUiThread {
                            Toast.makeText(this, "File received successfully!", Toast.LENGTH_SHORT).show()
                        }

                        val outputStream = socket.getOutputStream()
                        outputStream.write("ACK\n".toByteArray(StandardCharsets.UTF_8))
                        outputStream.flush()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    fileOutputStream?.close()
                    socket?.close()
                    serverSocket.close()
                }
            }.start()
        }
    }

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    private fun startPeerDiscovery() {
        Thread {
            val address = getIPAddress()

            try {
                val socket = DatagramSocket()
                socket.broadcast = true

                val discoveryMessage = "DISCOVER"
                val buffer = discoveryMessage.toByteArray(StandardCharsets.UTF_8)
                val packet = DatagramPacket(buffer, buffer.size, getBroadcastAddress(), discoveryPort)
                socket.send(packet)

                runOnUiThread {
                    Toast.makeText(this, "Peer discovery started.", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun getBroadcastAddress(): InetAddress {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcpInfo = wifiManager.dhcpInfo
        val broadcast = (dhcpInfo.ipAddress and dhcpInfo.netmask.inv()) or dhcpInfo.netmask.inv()

        val byteAddress = ByteArray(4)
        for (i in 0..3) {
            byteAddress[i] = (broadcast shr i * 8 and 0xFF).toByte()
        }

        return InetAddress.getByAddress(byteAddress)
    }

    private fun getIPAddress(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ip = wifiInfo.ipAddress

        return Formatter.formatIpAddress(ip)
    }

    private fun getPath(uri: Uri): String? {
        val projection = arrayOf(MediaStore.Files.FileColumns.DATA)
        val cursor = contentResolver.query(uri, projection, null, null, null)
        val columnIndex = cursor?.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
        cursor?.moveToFirst()
        val path = columnIndex?.let { cursor.getString(it) }
        cursor?.close()
        return path
    }
}
