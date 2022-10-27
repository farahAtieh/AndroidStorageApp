package com.plcoding.androidstorage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.plcoding.androidstorage.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.jar.Manifest

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var internalStoragePhotoAdapter: InternalStoragePhotoAdapter
    private lateinit var externalStoragePhotoAdapter: SharedPhotoAdapter

    private var readPermissionGranted = false
    private var writePermissionGranted = false
    private lateinit var permissionsLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        internalStoragePhotoAdapter = InternalStoragePhotoAdapter {
            val isDeletionSuccessful = deletePhotoFromInternalStorage(it.name)
            if (isDeletionSuccessful){
                loadPhotosFromInternalStorageIntoRecyclerview()
                Toast.makeText(this, "Photo successfully deleted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to delete photo", Toast.LENGTH_SHORT).show()
            }
        }

        externalStoragePhotoAdapter = SharedPhotoAdapter {

        }

        permissionsLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()){ permissions ->
            // keys are the permissions
            readPermissionGranted = permissions[android.Manifest.permission.READ_EXTERNAL_STORAGE] ?: readPermissionGranted
            writePermissionGranted = permissions[android.Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: writePermissionGranted

        }

        updateOrRequestPermissions()

        // give us the bitmap of the photo taken from the camera
        val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicturePreview()){
            //define where to save it internally or in a shared storage
            val isPrivate = binding.switchPrivate.isChecked
            val isSavedSuccessfully = when {
                isPrivate -> savePhotoToInternalStorage(UUID.randomUUID().toString(), it)
                writePermissionGranted -> savePhotoToExternalStorage(UUID.randomUUID().toString(), it)
                else -> false
            }

            if(isPrivate){
                loadPhotosFromInternalStorageIntoRecyclerview()
            }

            if (isSavedSuccessfully){
                Toast.makeText(this, "Photo saved successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to save photo", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnTakePhoto.setOnClickListener {
            takePhoto.launch()
        }

        setupInternalStorageIntoRecyclerview()
        loadPhotosFromInternalStorageIntoRecyclerview()
    }

    private fun updateOrRequestPermissions(){
        val hasReadPermission = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED


        val hasWritePermission = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

        val minSDK29 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        readPermissionGranted = hasReadPermission
        // because in sdks >= 29 we don't need the write permission
        writePermissionGranted = hasWritePermission || minSDK29

        val permissionsToRequest = mutableListOf<String>()

        if(!writePermissionGranted){
            permissionsToRequest.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if(!readPermissionGranted){
            permissionsToRequest.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        //todo handle declined
        if(permissionsToRequest.isNotEmpty()){
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun savePhotoToExternalStorage(displayName: String, bmp: Bitmap): Boolean{
        val imageCollection = sdk29AndUp {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.WIDTH, bmp.width)
            put(MediaStore.Images.Media.HEIGHT, bmp.height)
        }

        return try {
            contentResolver.insert(imageCollection, contentValues)?.also { uri ->
                 contentResolver.openOutputStream(uri).use { outputStream ->
                     if(!bmp.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)){
                         throw IOException("Couldn't save bitmap.")
                     }
                 }
            } ?: throw IOException("Couldn't create media store entry")
            true
        }catch (e: IOException){
            e.printStackTrace()
            false
        }
    }
    private fun setupInternalStorageIntoRecyclerview() = binding.rvPrivatePhotos.apply {
        adapter = internalStoragePhotoAdapter
        layoutManager = StaggeredGridLayoutManager(3, RecyclerView.VERTICAL)
    }

    private fun loadPhotosFromInternalStorageIntoRecyclerview(){
        lifecycleScope.launch {
            val photo = loadPhotoFromInternalStorage()
            internalStoragePhotoAdapter.submitList(photo)
        }
    }

    private fun deletePhotoFromInternalStorage(filename: String): Boolean{
        return try {
            deleteFile(filename)

        }catch (e: Exception){
            e.printStackTrace()
            false
        }
    }

    private suspend fun loadPhotoFromInternalStorage(): List<InternalStoragePhoto>{
        return withContext(Dispatchers.IO){
            val files = filesDir.listFiles()
            // filter to return only our images
            files?.filter {
                it.canRead() && it.isFile && it.name.endsWith(".jpg")
            }?.map {
                val bytes = it.readBytes()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                InternalStoragePhoto(it.name, bmp)
            }
        } ?: listOf()
    }

    private fun savePhotoToInternalStorage(filename: String, bmp: Bitmap): Boolean{
        return  try {
            openFileOutput("$filename.jpg", MODE_PRIVATE).use { stream ->
                if(!bmp.compress(Bitmap.CompressFormat.JPEG, 95, stream)){
                    throw IOException("Couldn't save bitmap.")
                }
                true
            }
        }catch (e: IOException){
            e.printStackTrace()
            false
        }
    }
}
