// "Remove annotation" "true"
// RUNTIME_WITH_SCRIPT_RUNTIME

@RequiresOptIn
annotation class SomeOptInAnnotation

@get:SomeOptInAnnotation<caret>
val someProperty: Int = 5
