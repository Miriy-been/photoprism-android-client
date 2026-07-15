package ua.com.radiokot.photoprism.features.people.view.model

import android.view.View
import androidx.core.view.ViewCompat
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.items.AbstractItem
import com.squareup.picasso.Picasso
import org.koin.core.component.KoinScopeComponent
import org.koin.core.component.inject
import org.koin.core.scope.Scope
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.databinding.ListItemPersonBinding
import ua.com.radiokot.photoprism.di.DI_SCOPE_SESSION
import ua.com.radiokot.photoprism.extension.hardwareConfigIfAvailable
import ua.com.radiokot.photoprism.features.gallery.logic.MediaPreviewUrlFactory
import ua.com.radiokot.photoprism.features.people.data.model.Person

class PersonListItem(
    val person: Person,
    private val mediaPreviewUrlFactory: MediaPreviewUrlFactory,
) : AbstractItem<PersonListItem.ViewHolder>() {

    override val type: Int = R.layout.list_item_person
    override val layoutRes: Int = R.layout.list_item_person
    override var identifier: Long = person.id.hashCode().toLong()

    override fun getViewHolder(v: View): ViewHolder =
        ViewHolder(v)

    class ViewHolder(
        itemView: View,
    ) : FastAdapter.ViewHolder<PersonListItem>(itemView),
        KoinScopeComponent {
        override val scope: Scope
            get() = getKoin().getScope(DI_SCOPE_SESSION)

        private val view = ListItemPersonBinding.bind(itemView)
        private val picasso: Picasso by inject()

        override fun bindView(item: PersonListItem, payloads: List<Any>) {
            val person = item.person

            view.personAvatarImageView.contentDescription = person.name

            val thumbnailUrl = item.mediaPreviewUrlFactory.getThumbnailUrl(
                thumbnailHash = person.thumbnailHash,
                sizePx = 250,
            )

            picasso
                .load(thumbnailUrl)
                .placeholder(R.drawable.image_placeholder_circle)
                .fit()
                .centerCrop()
                .into(view.personAvatarImageView)

            view.personNameTextView.text = person.name
                ?: view.personNameTextView.context.getString(R.string.unknown_person)

            view.personPhotoCountTextView.text = view.personPhotoCountTextView.resources.getQuantityString(
                R.plurals.photos_count,
                person.photoCount,
                person.photoCount
            )

            view.personFavoriteImageView.visibility = if (person.isFavorite) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }

            ViewCompat.setTooltipText(view.root, person.name)
        }

        override fun unbindView(item: PersonListItem) {
            picasso.cancelRequest(view.personAvatarImageView)
        }
    }
}
