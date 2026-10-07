alter table jdbc_driver_package
    add constraint uk_jdbc_driver_package_fingerprint unique (package_fingerprint);
