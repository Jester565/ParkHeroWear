/*
import sun.invoke.util.VerifyAccess.getPackageName
import android.graphics.drawable.Drawable
import android.support.wear.widget.drawer.WearableNavigationDrawerView

import android.graphics.drawable.Drawable
import android.support.wear.widget.drawer.WearableNavigationDrawerView

import sun.invoke.util.VerifyAccess.getPackageName

private class NavigationAdapter(private val mContext: Context) : WearableNavigationDrawerView.WearableNavigationDrawerAdapter() {

    override fun getCount(): Int {
        return mSolarSystem.size()
    }

    override fun getItemText(pos: Int): String {
        return mSolarSystem.get(pos).getName()
    }

    override fun getItemDrawable(pos: Int): Drawable {
        val navigationIcon = mSolarSystem.get(pos).getNavigationIcon()

        val drawableNavigationIconId = getResources().getIdentifier(navigationIcon, "drawable", getPackageName())

        return mContext.getDrawable(drawableNavigationIconId)
    }
}
*/