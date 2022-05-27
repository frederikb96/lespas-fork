package site.leos.apps.lespas.search

import android.annotation.SuppressLint
import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.*
import androidx.activity.OnBackPressedCallback
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.bonuspack.location.GeocoderNominatim
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.helper.OkHttpWebDav
import site.leos.apps.lespas.helper.SingleLiveEvent
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.sync.Action
import site.leos.apps.lespas.sync.ActionRepository
import java.io.IOException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.*

class LocationSearchHostFragment: Fragment() {
    private var loadingProgressBar: CircularProgressIndicator? = null
    private var menu: Menu? = null
    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private val searchViewModel: LocationSearchViewModel by viewModels { LocationSearchViewModelFactory(requireActivity().application, requireArguments().getInt(KEY_SEARCH_TARGET), imageLoaderModel) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        requireActivity().onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (childFragmentManager.backStackEntryCount > 1) childFragmentManager.popBackStack() else parentFragmentManager.popBackStack()
            }
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_location_search_host, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        searchViewModel.getProgress().observe(viewLifecycleOwner, Observer { progress ->
            when(progress) {
                0 -> loadingProgressBar?.isIndeterminate = true
                100 -> disableMenuItem(R.id.option_menu_search_progress)
                else -> loadingProgressBar?.apply {
                    isIndeterminate = false
                    setProgressCompat(progress, true)
                }

            }
        })

        if (childFragmentManager.backStackEntryCount == 0) childFragmentManager.beginTransaction().replace(R.id.container_child_fragment, LocationResultByLocalitiesFragment.newInstance(requireArguments().getInt(KEY_SEARCH_TARGET)), LocationResultByLocalitiesFragment::class.java.canonicalName).addToBackStack(null).commit()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.location_search_menu, menu)
        this.menu = menu
        menu.findItem(R.id.option_menu_search_progress)?.apply {
            loadingProgressBar = actionView.findViewById(R.id.search_progress)
            searchViewModel.getProgress().value?.also { progress-> if (progress == 100) this.disable() }
        }
        when(childFragmentManager.backStackEntryCount) {
            2-> menu.findItem(R.id.option_menu_in_map).enable()
        }
    }

    //private fun enableMenuItem(itemId: Int): MenuItem? = menu?.findItem(itemId)?.apply { this.enable() }
    private fun disableMenuItem(itemId: Int) { menu?.findItem(itemId)?.disable() }

    private fun MenuItem.disable() {
        this.isEnabled = false
        this.isVisible = false
    }
    private fun MenuItem.enable() {
        this.isEnabled = true
        this.isVisible = true
    }

    @Suppress("UNCHECKED_CAST")
    class LocationSearchViewModelFactory(private val application: Application, private val searchTarget: Int, private val remoteImageModel: NCShareViewModel): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LocationSearchViewModel(application, searchTarget, remoteImageModel) as T
    }

    class LocationSearchViewModel(application: Application, searchTarget: Int, remoteImageModel: NCShareViewModel): AndroidViewModel(application) {
        private val photoRepository = PhotoRepository(application)
        private val resultList = mutableListOf<LocationSearchResult>()
        private val result = MutableLiveData<List<LocationSearchResult>>()
        private val progress = SingleLiveEvent<Int>()
        private val patchActions = mutableListOf<Action>()

        @SuppressLint("RestrictedApi")
        private var job = viewModelScope.launch(Dispatchers.IO) {
            progress.postValue(0)
            when(searchTarget) {
                R.id.search_album -> PhotoRepository(application).getAllImageNotHidden()
                R.id.search_cameraroll -> Tools.getCameraRoll(application.contentResolver, true)
                else -> remoteImageModel.getCameraRollArchive()
            }.run {
                val lespasBaseFolder = application.getString(R.string.lespas_base_folder_name)
                val cr = application.contentResolver
                val albums = AlbumRepository(application).getAllAlbumAttribute()
                val total = this.size
                var rp = NCShareViewModel.RemotePhoto(Photo(dateTaken = LocalDateTime.MIN, lastModified = LocalDateTime.MIN), "")
                val nominatim = GeocoderNominatim(Locale.getDefault(), BuildConfig.APPLICATION_ID)

                var latitude = Photo.GPS_DATA_UNKNOWN
                var longitude = Photo.GPS_DATA_UNKNOWN
                var altitude = Photo.GPS_DATA_UNKNOWN
                var bearing = Photo.GPS_DATA_UNKNOWN
                val defaultOffset = OffsetDateTime.now().offset

                this.asReversed().forEachIndexed { i, photo ->
                    progress.postValue((i * 100.0 / total).toInt())
                    if (Tools.hasExif(photo.mimeType)) {
                        when(searchTarget) {
                            R.id.search_album -> {
                                if (photo.latitude != Photo.NO_GPS_DATA) doubleArrayOf(photo.latitude, photo.longitude)
                                else return@forEachIndexed
                            }
                            R.id.search_cameraroll -> {
                                try {
                                    cr.openInputStream(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(Uri.parse(photo.id)) else Uri.parse(photo.id))
                                } catch (e: SecurityException) {
                                    cr.openInputStream(Uri.parse(photo.id))
                                } catch (e: UnsupportedOperationException) {
                                    cr.openInputStream(Uri.parse(photo.id))
                                }?.let { try { ExifInterface(it).latLong } catch (e: NullPointerException) { return@forEachIndexed }
                                } ?: run { return@forEachIndexed }
                            }
                            R.id.search_archive -> {
                                when(photo.latitude) {
                                    Photo.NO_GPS_DATA -> return@forEachIndexed
                                    Photo.GPS_DATA_UNKNOWN -> remoteImageModel.getMediaExif(NCShareViewModel.RemotePhoto(photo, "/DCIM"))?.first?.let { exif ->
                                        exif.latLong?.run {
                                            latitude = this[0]
                                            longitude = this[1]
                                            altitude = exif.getAltitude(Photo.NO_GPS_DATA)
                                            bearing = Tools.getBearing(exif)
                                        } ?: run {
                                            latitude = Photo.NO_GPS_DATA
                                            longitude = Photo.NO_GPS_DATA
                                            altitude = Photo.NO_GPS_DATA
                                            bearing = Photo.NO_GPS_DATA
                                        }

                                        // Patch WebDAV properties in archive
                                        patchActions.add(Action(null, Action.ACTION_PATCH_PROPERTIES, "","/DCIM",
                                            "<oc:${OkHttpWebDav.LESPAS_LATITUDE}>" + latitude + "</oc:${OkHttpWebDav.LESPAS_LATITUDE}>" +
                                                    "<oc:${OkHttpWebDav.LESPAS_LONGITUDE}>" + longitude + "</oc:${OkHttpWebDav.LESPAS_LONGITUDE}>" +
                                                    "<oc:${OkHttpWebDav.LESPAS_ALTITUDE}>" + altitude + "</oc:${OkHttpWebDav.LESPAS_ALTITUDE}>" +
                                                    "<oc:${OkHttpWebDav.LESPAS_BEARING}>" + bearing + "</oc:${OkHttpWebDav.LESPAS_BEARING}>" +
                                                    "<oc:${OkHttpWebDav.LESPAS_ORIENTATION}>" + exif.rotationDegrees + "</oc:${OkHttpWebDav.LESPAS_ORIENTATION}>" +
                                                    "<oc:${OkHttpWebDav.LESPAS_WIDTH}>" + exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) + "</oc:${OkHttpWebDav.LESPAS_WIDTH}>" +
                                                    "<oc:${OkHttpWebDav.LESPAS_HEIGHT}>" + exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0) + "</oc:${OkHttpWebDav.LESPAS_HEIGHT}>" +
                                                    "<oc:${OkHttpWebDav.LESPAS_DATE_TAKEN}>" + (exif.dateTimeOriginal ?: exif.dateTimeDigitized ?: (photo.dateTaken.toEpochSecond(defaultOffset) * 1000)) + "</oc:${OkHttpWebDav.LESPAS_DATE_TAKEN}>",
                                            photo.name, System.currentTimeMillis(), 1)
                                        )

                                        exif.latLong
                                    }
                                    else -> doubleArrayOf(photo.latitude, photo.longitude)
                                }
                            }
                            else -> null
                        }?.also { latLong ->
                            if (photo.country.isEmpty()) {
                                try {
                                    nominatim.getFromLocation(latLong[0], latLong[1], 1)
                                } catch (e: IOException) { null }?.get(0)?.let {
                                    if (it.countryName != null) {
                                        val locality = it.locality ?: it.adminArea ?: ""
                                        if (searchTarget == R.id.search_album) photoRepository.updateAddress(photo.id, locality, it.countryName, it.countryCode ?: "")
                                        Pair(it.countryName, locality)
                                    } else null
                                } ?: run { null }
                            } else {
                                Pair(photo.country, photo.locality)
                            }?.apply {
                                if (searchTarget == R.id.search_album) {
                                    val album = albums.find { it.id == photo.albumId }
                                    album?.let {
                                        rp = NCShareViewModel.RemotePhoto(photo, if (album.shareId and Album.REMOTE_ALBUM == Album.REMOTE_ALBUM && photo.eTag != Photo.ETAG_NOT_YET_UPLOADED) "${lespasBaseFolder}/${album.name}" else "")
                                    } ?: run {
                                        return@forEachIndexed
                                    }
                                } else rp = NCShareViewModel.RemotePhoto(photo.copy(latitude = latLong[0], longitude = latLong[1]), if (searchTarget == R.id.search_cameraroll) "" else "/DCIM")

                                resultList.find { result -> result.country == this.first && result.locality == this.second }
                                    ?.let { existed ->
                                        existed.photos.add(rp)
                                        existed.total++
                                    }
                                    ?: run { resultList.add(LocationSearchResult(arrayListOf(rp), 1, this.first, this.second)) }

                                // Update UI
                                result.postValue(resultList)
                            }
                        }
                    }
                }
            }

            // Show progress to the end
            delay(500)
            progress.postValue(100)
        }.apply { invokeOnCompletion { if (patchActions.isNotEmpty()) ActionRepository(getApplication()).addActions(patchActions) }}

        override fun onCleared() {
            job.cancel()
            super.onCleared()
        }

        fun getResult(): LiveData<List<LocationSearchResult>> = result
        fun getProgress(): SingleLiveEvent<Int> = progress

/*
        private var currentLocality = ""
        fun getCurrentLocality(): String = currentLocality
        fun putCurrentLocality(locality: String) { currentLocality = locality }
*/
    }

    data class LocationSearchResult (
        var photos: MutableList<NCShareViewModel.RemotePhoto>,
        var total: Int,
        val country: String,
        val locality: String,
    )

    companion object {
        private const val KEY_SEARCH_TARGET = "KEY_SEARCH_TARGET"

        @JvmStatic
        fun newInstance(target: Int) = LocationSearchHostFragment().apply { arguments = Bundle().apply { putInt(KEY_SEARCH_TARGET, target) } }
    }
}