# Keep custom view classes and their public methods
-keep public class com.github.kr328.clash.design.** {
    public *;
}

# Keep custom view classes for data binding
-keep class * extends android.databinding.ViewDataBinding
-keep class * extends android.view.View

# Keep binding classes
-keep class com.github.kr328.clash.design.databinding.** {
    *;
}

# Keep RecyclerView adapter classes
-keep class * extends androidx.recyclerview.widget.RecyclerView.Adapter {
    *;
}

# Keep model classes for data binding
-keep class com.github.kr328.clash.design.model.** {
    *;
}

# Keep utility classes
-keep class com.github.kr328.clash.design.util.** {
    *;
}

# Keep preference classes
-keep class com.github.kr328.clash.design.preference.** {
    *;
}

# Keep component classes
-keep class com.github.kr328.clash.design.component.** {
    *;
}

# Keep dialog classes
-keep class com.github.kr328.clash.design.dialog.** {
    *;
}

# Keep adapter classes
-keep class com.github.kr328.clash.design.adapter.** {
    *;
}

# Keep store classes
-keep class com.github.kr328.clash.design.store.** {
    *;
}

# Keep ui classes
-keep class com.github.kr328.clash.design.ui.** {
    *;
}

# Keep view classes
-keep class com.github.kr328.clash.design.view.** {
    *;
}
