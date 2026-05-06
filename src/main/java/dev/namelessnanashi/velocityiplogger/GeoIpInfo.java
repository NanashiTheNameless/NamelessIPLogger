package dev.namelessnanashi.velocityiplogger;

public record GeoIpInfo(
	String status,
	String country,
	String countryCode,
	String region,
	String city,
	String timezone,
	String isp,
	String asnNumber,
	String asnOrganization,
	String latitude,
	String longitude,
	String message
) {
	public static GeoIpInfo unavailable() {
		return new GeoIpInfo(
			"unavailable",
			"",
			"",
			"",
			"",
			"",
			"",
			"",
			"",
			"",
			"",
			""
		);
	}

	public boolean isSuccess() {
		return "success".equalsIgnoreCase(status);
	}

	public String shortDescription() {
		if (!isSuccess()) {
			return "unavailable";
		}
		final String cityPart = city == null || city.isBlank() ? "unknown-city" : city;
		final String countryPart = country == null || country.isBlank() ? "unknown-country" : country;
		final String asnPart = asnNumber == null || asnNumber.isBlank() ? "asn-unavailable" : asnNumber;
		return cityPart + ", " + countryPart + " (" + asnPart + ")";
	}
}
