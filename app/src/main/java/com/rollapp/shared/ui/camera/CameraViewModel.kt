package com.rollapp.shared.ui.camera

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rollapp.shared.domain.repository.PhotoRepository
import com.rollapp.shared.ui.navigation.NavArgs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Backs the gallery shortcut inside the camera.
 *
 * Deliberately tiny and separate from GroupViewModel: the camera needs somewhere to
 * send photos, not the group's photo feed, member list and realtime listeners.
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val groupId: String = checkNotNull(savedStateHandle[NavArgs.GROUP_ID])

    fun uploadFromGallery(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            uris.forEach { uri ->
                photoRepository.enqueueUpload(
                    groupId = groupId,
                    localUri = uri,
                    caption = null,
                    capturedAt = System.currentTimeMillis()
                )
            }
        }
    }
}
