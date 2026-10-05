package com.pigeon.blackbox.user;

public enum Plan {

	/* No payment involved: never appears in a SUBSCRIPTION_PAID event */
	FREE,
	
	PRO,
	BUSINESS
}
