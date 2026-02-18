package com.example.rocketplan_android.data.model

data class CountryCode(
    val name: String,
    val dialCode: String,
    val code: String,
    val flag: String
) {
    val displayName: String get() = "$flag $name ($dialCode)"
    val shortDisplay: String get() = "$flag $dialCode"

    companion object {
        val ALL: List<CountryCode> = listOf(
            CountryCode("United States", "+1", "US", "\uD83C\uDDFA\uD83C\uDDF8"),
            CountryCode("Canada", "+1", "CA", "\uD83C\uDDE8\uD83C\uDDE6"),
            CountryCode("United Kingdom", "+44", "GB", "\uD83C\uDDEC\uD83C\uDDE7"),
            CountryCode("Australia", "+61", "AU", "\uD83C\uDDE6\uD83C\uDDFA"),
            CountryCode("Germany", "+49", "DE", "\uD83C\uDDE9\uD83C\uDDEA"),
            CountryCode("France", "+33", "FR", "\uD83C\uDDEB\uD83C\uDDF7"),
            CountryCode("India", "+91", "IN", "\uD83C\uDDEE\uD83C\uDDF3"),
            CountryCode("Mexico", "+52", "MX", "\uD83C\uDDF2\uD83C\uDDFD"),
            CountryCode("Brazil", "+55", "BR", "\uD83C\uDDE7\uD83C\uDDF7"),
            CountryCode("Japan", "+81", "JP", "\uD83C\uDDEF\uD83C\uDDF5"),
            CountryCode("South Korea", "+82", "KR", "\uD83C\uDDF0\uD83C\uDDF7"),
            CountryCode("Italy", "+39", "IT", "\uD83C\uDDEE\uD83C\uDDF9"),
            CountryCode("Spain", "+34", "ES", "\uD83C\uDDEA\uD83C\uDDF8"),
            CountryCode("Netherlands", "+31", "NL", "\uD83C\uDDF3\uD83C\uDDF1"),
            CountryCode("Sweden", "+46", "SE", "\uD83C\uDDF8\uD83C\uDDEA"),
            CountryCode("Norway", "+47", "NO", "\uD83C\uDDF3\uD83C\uDDF4"),
            CountryCode("Denmark", "+45", "DK", "\uD83C\uDDE9\uD83C\uDDF0"),
            CountryCode("Finland", "+358", "FI", "\uD83C\uDDEB\uD83C\uDDEE"),
            CountryCode("Switzerland", "+41", "CH", "\uD83C\uDDE8\uD83C\uDDED"),
            CountryCode("Ireland", "+353", "IE", "\uD83C\uDDEE\uD83C\uDDEA"),
            CountryCode("New Zealand", "+64", "NZ", "\uD83C\uDDF3\uD83C\uDDFF"),
            CountryCode("Singapore", "+65", "SG", "\uD83C\uDDF8\uD83C\uDDEC"),
            CountryCode("Philippines", "+63", "PH", "\uD83C\uDDF5\uD83C\uDDED"),
            CountryCode("Colombia", "+57", "CO", "\uD83C\uDDE8\uD83C\uDDF4"),
            CountryCode("Argentina", "+54", "AR", "\uD83C\uDDE6\uD83C\uDDF7"),
            CountryCode("Chile", "+56", "CL", "\uD83C\uDDE8\uD83C\uDDF1"),
            CountryCode("Peru", "+51", "PE", "\uD83C\uDDF5\uD83C\uDDEA"),
            CountryCode("South Africa", "+27", "ZA", "\uD83C\uDDFF\uD83C\uDDE6"),
            CountryCode("Israel", "+972", "IL", "\uD83C\uDDEE\uD83C\uDDF1"),
            CountryCode("United Arab Emirates", "+971", "AE", "\uD83C\uDDE6\uD83C\uDDEA"),
            CountryCode("Poland", "+48", "PL", "\uD83C\uDDF5\uD83C\uDDF1"),
            CountryCode("Portugal", "+351", "PT", "\uD83C\uDDF5\uD83C\uDDF9"),
            CountryCode("Austria", "+43", "AT", "\uD83C\uDDE6\uD83C\uDDF9"),
            CountryCode("Belgium", "+32", "BE", "\uD83C\uDDE7\uD83C\uDDEA"),
            CountryCode("Czech Republic", "+420", "CZ", "\uD83C\uDDE8\uD83C\uDDFF"),
            CountryCode("Romania", "+40", "RO", "\uD83C\uDDF7\uD83C\uDDF4"),
            CountryCode("Hungary", "+36", "HU", "\uD83C\uDDED\uD83C\uDDFA"),
            CountryCode("Greece", "+30", "GR", "\uD83C\uDDEC\uD83C\uDDF7"),
            CountryCode("Turkey", "+90", "TR", "\uD83C\uDDF9\uD83C\uDDF7"),
            CountryCode("Thailand", "+66", "TH", "\uD83C\uDDF9\uD83C\uDDED"),
            CountryCode("Malaysia", "+60", "MY", "\uD83C\uDDF2\uD83C\uDDFE"),
            CountryCode("Indonesia", "+62", "ID", "\uD83C\uDDEE\uD83C\uDDE9"),
            CountryCode("Vietnam", "+84", "VN", "\uD83C\uDDFB\uD83C\uDDF3"),
            CountryCode("Nigeria", "+234", "NG", "\uD83C\uDDF3\uD83C\uDDEC"),
            CountryCode("Egypt", "+20", "EG", "\uD83C\uDDEA\uD83C\uDDEC"),
            CountryCode("Pakistan", "+92", "PK", "\uD83C\uDDF5\uD83C\uDDF0"),
            CountryCode("Bangladesh", "+880", "BD", "\uD83C\uDDE7\uD83C\uDDE9"),
            CountryCode("Ukraine", "+380", "UA", "\uD83C\uDDFA\uD83C\uDDE6"),
            CountryCode("Saudi Arabia", "+966", "SA", "\uD83C\uDDF8\uD83C\uDDE6"),
            CountryCode("Puerto Rico", "+1", "PR", "\uD83C\uDDF5\uD83C\uDDF7")
        )

        val DEFAULT: CountryCode = ALL.first()
    }
}
