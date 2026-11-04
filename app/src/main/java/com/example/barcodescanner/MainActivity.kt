package com.example.barcodescanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.room.*
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val value: String,
    val timestamp: String
)

@Dao
interface ScanDao {
    @Query("SELECT * FROM scans ORDER BY id DESC")
    fun getAllScans(): Flow<List<ScanEntity>>

    @Query("SELECT * FROM scans")
    suspend fun getAllScansList(): List<ScanEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScan(scan: ScanEntity)

    @Query("DELETE FROM scans")
    suspend fun clearAll()
}

@Database(entities = [ScanEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "barcode_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = AppDatabase.getDatabase(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BarcodeApp(database)
                }
            }
        }
    }
}

@Composable
fun BarcodeApp(database: AppDatabase) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasCameraPermission = isGranted }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (hasCameraPermission) {
        MainScreen(database)
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera permission required to scan barcodes.")
        }
    }
}

@Composable
fun MainScreen(database: AppDatabase) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val allScans by database.scanDao().getAllScans().collectAsState(initial = emptyList())

    var currentScannedValue by remember { mutableStateOf<String?>(null) }
    var showScanNextDialog by remember { mutableStateOf(false) }
    var showEmailDialog by remember { mutableStateOf(false) }
    var generatedExcelFile by remember { mutableStateOf<File?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!showScanNextDialog && !showEmailDialog) {
            CameraPreview(onBarcodeScanned = { barcode ->
                if (currentScannedValue == null) {
                    currentScannedValue = barcode
                    playBeep()
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    coroutineScope.launch {
                        database.scanDao().insertScan(ScanEntity(value = barcode, timestamp = timestamp))
                    }
                    showScanNextDialog = true
                }
            })
        }

        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(text = "Total Scans in DB: ${allScans.size}", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        }

        if (showScanNextDialog) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Barcode Scanned & Saved!") },
                text = {
                    Column {
                        Text("Scanned Value:")
                        Text(currentScannedValue ?: "", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Total database records: ${allScans.size}")
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        currentScannedValue = null
                        showScanNextDialog = false
                    }) { Text("Scan Next Barcode") }
                },
                dismissButton = {
                    OutlinedButton(onClick = {
                        coroutineScope.launch {
                            val fullList = database.scanDao().getAllScansList()
                            val file = createExcelFile(context, fullList)
                            if (file != null) {
                                generatedExcelFile = file
                                showScanNextDialog = false
                                showEmailDialog = true
                            } else {
                                Toast.makeText(context, "Failed to create Excel file", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("Export to Excel") }
                }
            )
        }

        if (showEmailDialog && generatedExcelFile != null) {
            EmailDialog(file = generatedExcelFile!!, onDismiss = {
                showEmailDialog = false
                currentScannedValue = null
            })
        }
    }
}

fun playBeep() {
    try {
        val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    } catch (e: Exception) { e.printStackTrace() }
}

@Composable
fun CameraPreview(onBarcodeScanned: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val imageAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

                imageAnalysis.setAnalyzer(cameraExecutor, BarcodeAnalyzer { barcode -> onBarcodeScanned(barcode) })

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                } catch (e: Exception) { e.printStackTrace() }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

class BarcodeAnalyzer(private val onBarcodeScanned: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient()

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        barcode.rawValue?.let { rawValue ->
                            onBarcodeScanned(rawValue)
                            break
                        }
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        } else { imageProxy.close() }
    }
}

@Composable
fun EmailDialog(file: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var emailAddress by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Complete") },
        text = {
            Column {
                Text("Excel file saved: ${file.name}")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = emailAddress, onValueChange = { emailAddress = it }, label = { Text("Enter Email Address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = {
                sendEmailWithAttachment(context, emailAddress, file)
                onDismiss()
            }) { Text("Send Email") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

fun createExcelFile(context: Context, items: List<ScanEntity>): File? {
    return try {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Barcodes")

        val headerRow = sheet.createRow(0)
        headerRow.createCell(0).setCellValue("ID")
        headerRow.createCell(1).setCellValue("Barcode Value")
        headerRow.createCell(2).setCellValue("Timestamp")

        items.forEachIndexed { index, scan ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(scan.id.toDouble())
            row.createCell(1).setCellValue(scan.value)
            row.createCell(2).setCellValue(scan.timestamp)
        }

        val fileName = "Barcodes_${System.currentTimeMillis()}.xlsx"
        val file = File(context.cacheDir, fileName)
        val fileOut = FileOutputStream(file)
        workbook.write(fileOut)
        fileOut.close()
        workbook.close()
        file
    } catch (e: Exception) { e.printStackTrace(); null }
}

fun sendEmailWithAttachment(context: Context, recipientEmail: String, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(recipientEmail))
        putExtra(Intent.EXTRA_SUBJECT, "Scanned Barcodes Export")
        putExtra(Intent.EXTRA_TEXT, "Attached is the Excel spreadsheet containing the scanned barcodes database.")
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Send email using..."))
}
