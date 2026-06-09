package ua.com.radiokot.photoprism.features.upload

import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.module.Module
import org.koin.dsl.module
import ua.com.radiokot.photoprism.features.upload.view.model.UploadFilesViewModel

val uploadFeatureModule: Module = module {
    viewModel {
        UploadFilesViewModel(
            albumsRepository = get(),
            session = get(),
            jsonObjectMapper = get(),
            application = get(),
        )
    }
}
