package com.pigeon.blackbox.event;

public enum Platform {

	WEB,
	IOS,
	ANDROID,
	DESKTOP,

	/** Call to the public API, authenticated by an API key: no interactive session. */
	API,

	/** Emitted by the backend itself, with no client involved (e.g. notifications). */
	SYSTEM
}
