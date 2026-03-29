idf_build_get_property(project_dir PROJECT_DIR)
idf_component_register(SRCS "plinky_plonky.c" "tmc2209.c"
                       INCLUDE_DIRS "."
                       PRIV_REQUIRES esp_driver_gpio esp_driver_uart esp_timer esp_event esp_event nvs_flash bt)
target_compile_options(${COMPONENT_LIB} PRIVATE -Wall -Wextra -Werror)
