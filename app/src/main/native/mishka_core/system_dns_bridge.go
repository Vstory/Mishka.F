package main

/*
#include <stdlib.h>
*/
import "C"

//export mishkaUpdateSystemDNS
func mishkaUpdateSystemDNS(cServers *C.char) *C.char {
	return guardString(func() string {
		if err := updateSystemDNSCommaList(C.GoString(cServers)); err != nil {
			return "error: " + err.Error()
		}
		return "ok"
	})
}
