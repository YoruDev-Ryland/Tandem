using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace EmuSync.Server.Data.Migrations.Sqlite
{
    /// <inheritdoc />
    public partial class Initial : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "ServerSettings",
                columns: table => new
                {
                    Key = table.Column<string>(type: "TEXT", nullable: false),
                    Value = table.Column<string>(type: "TEXT", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_ServerSettings", x => x.Key);
                });

            migrationBuilder.CreateTable(
                name: "Users",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    Email = table.Column<string>(type: "TEXT", nullable: false),
                    PasswordHash = table.Column<string>(type: "TEXT", nullable: false),
                    IsAdmin = table.Column<bool>(type: "INTEGER", nullable: false),
                    CreatedUtc = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Users", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "ActivityEvents",
                columns: table => new
                {
                    Id = table.Column<long>(type: "INTEGER", nullable: false)
                        .Annotation("Sqlite:Autoincrement", true),
                    UserId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Utc = table.Column<long>(type: "INTEGER", nullable: false),
                    Kind = table.Column<string>(type: "TEXT", nullable: false),
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: true),
                    EmulatorId = table.Column<string>(type: "TEXT", nullable: true),
                    GameKey = table.Column<string>(type: "TEXT", nullable: true),
                    Message = table.Column<string>(type: "TEXT", nullable: false),
                    DataJson = table.Column<string>(type: "TEXT", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_ActivityEvents", x => x.Id);
                    table.ForeignKey(
                        name: "FK_ActivityEvents_Users_UserId",
                        column: x => x.UserId,
                        principalTable: "Users",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "ConfigBundles",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    UserId = table.Column<Guid>(type: "TEXT", nullable: false),
                    EmulatorId = table.Column<string>(type: "TEXT", nullable: false),
                    OwnerDeviceId = table.Column<Guid>(type: "TEXT", nullable: true),
                    GroupId = table.Column<Guid>(type: "TEXT", nullable: true),
                    HeadVersionId = table.Column<Guid>(type: "TEXT", nullable: true),
                    OpenConflict = table.Column<bool>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_ConfigBundles", x => x.Id);
                    table.ForeignKey(
                        name: "FK_ConfigBundles_Users_UserId",
                        column: x => x.UserId,
                        principalTable: "Users",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "Devices",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    UserId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Name = table.Column<string>(type: "TEXT", nullable: false),
                    Platform = table.Column<string>(type: "TEXT", nullable: false),
                    AppVersion = table.Column<string>(type: "TEXT", nullable: false),
                    KeyHash = table.Column<string>(type: "TEXT", nullable: false),
                    CreatedUtc = table.Column<long>(type: "INTEGER", nullable: false),
                    LastSeenUtc = table.Column<long>(type: "INTEGER", nullable: true),
                    ShizukuOk = table.Column<bool>(type: "INTEGER", nullable: true),
                    ShizukuOffSinceUtc = table.Column<long>(type: "INTEGER", nullable: true),
                    RevokedUtc = table.Column<long>(type: "INTEGER", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Devices", x => x.Id);
                    table.ForeignKey(
                        name: "FK_Devices_Users_UserId",
                        column: x => x.UserId,
                        principalTable: "Users",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "PairingCodes",
                columns: table => new
                {
                    CodeHash = table.Column<string>(type: "TEXT", nullable: false),
                    UserId = table.Column<Guid>(type: "TEXT", nullable: false),
                    ExpiresUtc = table.Column<long>(type: "INTEGER", nullable: false),
                    UsedUtc = table.Column<long>(type: "INTEGER", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_PairingCodes", x => x.CodeHash);
                    table.ForeignKey(
                        name: "FK_PairingCodes_Users_UserId",
                        column: x => x.UserId,
                        principalTable: "Users",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "SaveItems",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    UserId = table.Column<Guid>(type: "TEXT", nullable: false),
                    EmulatorId = table.Column<string>(type: "TEXT", nullable: false),
                    Kind = table.Column<string>(type: "TEXT", maxLength: 16, nullable: false),
                    GameKey = table.Column<string>(type: "TEXT", nullable: false),
                    Slot = table.Column<string>(type: "TEXT", nullable: false),
                    Core = table.Column<string>(type: "TEXT", nullable: false),
                    HeadVersionId = table.Column<Guid>(type: "TEXT", nullable: true),
                    OpenConflict = table.Column<bool>(type: "INTEGER", nullable: false),
                    DisplayName = table.Column<string>(type: "TEXT", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_SaveItems", x => x.Id);
                    table.ForeignKey(
                        name: "FK_SaveItems_Users_UserId",
                        column: x => x.UserId,
                        principalTable: "Users",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "SyncGroups",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    UserId = table.Column<Guid>(type: "TEXT", nullable: false),
                    EmulatorId = table.Column<string>(type: "TEXT", nullable: false),
                    Name = table.Column<string>(type: "TEXT", nullable: false),
                    BundleId = table.Column<Guid>(type: "TEXT", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_SyncGroups", x => x.Id);
                    table.ForeignKey(
                        name: "FK_SyncGroups_Users_UserId",
                        column: x => x.UserId,
                        principalTable: "Users",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "UserBlobs",
                columns: table => new
                {
                    UserId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Hash = table.Column<string>(type: "TEXT", nullable: false),
                    Size = table.Column<long>(type: "INTEGER", nullable: false),
                    CreatedUtc = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_UserBlobs", x => new { x.UserId, x.Hash });
                    table.ForeignKey(
                        name: "FK_UserBlobs_Users_UserId",
                        column: x => x.UserId,
                        principalTable: "Users",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "UserSettings",
                columns: table => new
                {
                    UserId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Key = table.Column<string>(type: "TEXT", nullable: false),
                    Value = table.Column<string>(type: "TEXT", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_UserSettings", x => new { x.UserId, x.Key });
                    table.ForeignKey(
                        name: "FK_UserSettings_Users_UserId",
                        column: x => x.UserId,
                        principalTable: "Users",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "VaultItems",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    UserId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Category = table.Column<string>(type: "TEXT", nullable: false),
                    EmulatorId = table.Column<string>(type: "TEXT", nullable: true),
                    Name = table.Column<string>(type: "TEXT", nullable: false),
                    HeadVersionId = table.Column<Guid>(type: "TEXT", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_VaultItems", x => x.Id);
                    table.ForeignKey(
                        name: "FK_VaultItems_Users_UserId",
                        column: x => x.UserId,
                        principalTable: "Users",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "WebSessions",
                columns: table => new
                {
                    TokenHash = table.Column<string>(type: "TEXT", nullable: false),
                    UserId = table.Column<Guid>(type: "TEXT", nullable: false),
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: true),
                    CreatedUtc = table.Column<long>(type: "INTEGER", nullable: false),
                    LastUsedUtc = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_WebSessions", x => x.TokenHash);
                    table.ForeignKey(
                        name: "FK_WebSessions_Users_UserId",
                        column: x => x.UserId,
                        principalTable: "Users",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "ConfigVersions",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    BundleId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Seq = table.Column<int>(type: "INTEGER", nullable: false),
                    BaseVersionId = table.Column<Guid>(type: "TEXT", nullable: true),
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: true),
                    Source = table.Column<string>(type: "TEXT", maxLength: 16, nullable: false),
                    ReceivedUtc = table.Column<long>(type: "INTEGER", nullable: false),
                    ManifestJson = table.Column<string>(type: "TEXT", nullable: false),
                    ManifestHash = table.Column<string>(type: "TEXT", nullable: false),
                    TotalSize = table.Column<long>(type: "INTEGER", nullable: false),
                    Pinned = table.Column<bool>(type: "INTEGER", nullable: false),
                    ConflictReason = table.Column<string>(type: "TEXT", maxLength: 16, nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_ConfigVersions", x => x.Id);
                    table.ForeignKey(
                        name: "FK_ConfigVersions_ConfigBundles_BundleId",
                        column: x => x.BundleId,
                        principalTable: "ConfigBundles",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "Deliveries",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Kind = table.Column<string>(type: "TEXT", maxLength: 16, nullable: false),
                    TargetId = table.Column<Guid>(type: "TEXT", nullable: false),
                    VersionId = table.Column<Guid>(type: "TEXT", nullable: false),
                    CreatedUtc = table.Column<long>(type: "INTEGER", nullable: false),
                    AckedUtc = table.Column<long>(type: "INTEGER", nullable: true),
                    Result = table.Column<string>(type: "TEXT", maxLength: 32, nullable: true),
                    NotesJson = table.Column<string>(type: "TEXT", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Deliveries", x => x.Id);
                    table.ForeignKey(
                        name: "FK_Deliveries_Devices_DeviceId",
                        column: x => x.DeviceId,
                        principalTable: "Devices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "DeviceConfigs",
                columns: table => new
                {
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: false),
                    EmulatorId = table.Column<string>(type: "TEXT", nullable: false),
                    BundleId = table.Column<Guid>(type: "TEXT", nullable: false),
                    AppliedVersionId = table.Column<Guid>(type: "TEXT", nullable: true),
                    LastRawVersionId = table.Column<Guid>(type: "TEXT", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_DeviceConfigs", x => new { x.DeviceId, x.EmulatorId });
                    table.ForeignKey(
                        name: "FK_DeviceConfigs_Devices_DeviceId",
                        column: x => x.DeviceId,
                        principalTable: "Devices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "DeviceEmulators",
                columns: table => new
                {
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: false),
                    EmulatorId = table.Column<string>(type: "TEXT", nullable: false),
                    Package = table.Column<string>(type: "TEXT", nullable: false),
                    Version = table.Column<string>(type: "TEXT", nullable: false),
                    CoresJson = table.Column<string>(type: "TEXT", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_DeviceEmulators", x => new { x.DeviceId, x.EmulatorId });
                    table.ForeignKey(
                        name: "FK_DeviceEmulators_Devices_DeviceId",
                        column: x => x.DeviceId,
                        principalTable: "Devices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "DeviceRoots",
                columns: table => new
                {
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Token = table.Column<string>(type: "TEXT", nullable: false),
                    Path = table.Column<string>(type: "TEXT", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_DeviceRoots", x => new { x.DeviceId, x.Token });
                    table.ForeignKey(
                        name: "FK_DeviceRoots_Devices_DeviceId",
                        column: x => x.DeviceId,
                        principalTable: "Devices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "InstallRecords",
                columns: table => new
                {
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: false),
                    EmulatorId = table.Column<string>(type: "TEXT", nullable: false),
                    GameKey = table.Column<string>(type: "TEXT", nullable: false),
                    DisplayName = table.Column<string>(type: "TEXT", nullable: true),
                    Crc32 = table.Column<string>(type: "TEXT", nullable: true),
                    SeenUtc = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_InstallRecords", x => new { x.DeviceId, x.EmulatorId, x.GameKey });
                    table.ForeignKey(
                        name: "FK_InstallRecords_Devices_DeviceId",
                        column: x => x.DeviceId,
                        principalTable: "Devices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "RawConfigUploads",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: false),
                    EmulatorId = table.Column<string>(type: "TEXT", nullable: false),
                    ManifestJson = table.Column<string>(type: "TEXT", nullable: false),
                    ReceivedUtc = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_RawConfigUploads", x => x.Id);
                    table.ForeignKey(
                        name: "FK_RawConfigUploads_Devices_DeviceId",
                        column: x => x.DeviceId,
                        principalTable: "Devices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "DeviceItemStates",
                columns: table => new
                {
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: false),
                    ItemId = table.Column<Guid>(type: "TEXT", nullable: false),
                    KnownVersionId = table.Column<Guid>(type: "TEXT", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_DeviceItemStates", x => new { x.DeviceId, x.ItemId });
                    table.ForeignKey(
                        name: "FK_DeviceItemStates_Devices_DeviceId",
                        column: x => x.DeviceId,
                        principalTable: "Devices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "FK_DeviceItemStates_SaveItems_ItemId",
                        column: x => x.ItemId,
                        principalTable: "SaveItems",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "SaveVersions",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    ItemId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Seq = table.Column<int>(type: "INTEGER", nullable: false),
                    BaseVersionId = table.Column<Guid>(type: "TEXT", nullable: true),
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: true),
                    ReceivedUtc = table.Column<long>(type: "INTEGER", nullable: false),
                    ManifestJson = table.Column<string>(type: "TEXT", nullable: false),
                    ManifestHash = table.Column<string>(type: "TEXT", nullable: false),
                    TotalSize = table.Column<long>(type: "INTEGER", nullable: false),
                    Core = table.Column<string>(type: "TEXT", nullable: false),
                    Variant = table.Column<string>(type: "TEXT", nullable: true),
                    Pinned = table.Column<bool>(type: "INTEGER", nullable: false),
                    ConflictReason = table.Column<string>(type: "TEXT", maxLength: 16, nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_SaveVersions", x => x.Id);
                    table.ForeignKey(
                        name: "FK_SaveVersions_SaveItems_ItemId",
                        column: x => x.ItemId,
                        principalTable: "SaveItems",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "VaultVersions",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    ItemId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Seq = table.Column<int>(type: "INTEGER", nullable: false),
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: true),
                    ReceivedUtc = table.Column<long>(type: "INTEGER", nullable: false),
                    ManifestJson = table.Column<string>(type: "TEXT", nullable: false),
                    ManifestHash = table.Column<string>(type: "TEXT", nullable: false),
                    TotalSize = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_VaultVersions", x => x.Id);
                    table.ForeignKey(
                        name: "FK_VaultVersions_VaultItems_ItemId",
                        column: x => x.ItemId,
                        principalTable: "VaultItems",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_ActivityEvents_UserId_Utc",
                table: "ActivityEvents",
                columns: new[] { "UserId", "Utc" });

            migrationBuilder.CreateIndex(
                name: "IX_ConfigBundles_UserId_EmulatorId",
                table: "ConfigBundles",
                columns: new[] { "UserId", "EmulatorId" });

            migrationBuilder.CreateIndex(
                name: "IX_ConfigVersions_BundleId_Seq",
                table: "ConfigVersions",
                columns: new[] { "BundleId", "Seq" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_Deliveries_DeviceId_AckedUtc",
                table: "Deliveries",
                columns: new[] { "DeviceId", "AckedUtc" });

            migrationBuilder.CreateIndex(
                name: "IX_DeviceItemStates_ItemId",
                table: "DeviceItemStates",
                column: "ItemId");

            migrationBuilder.CreateIndex(
                name: "IX_Devices_KeyHash",
                table: "Devices",
                column: "KeyHash",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_Devices_UserId",
                table: "Devices",
                column: "UserId");

            migrationBuilder.CreateIndex(
                name: "IX_PairingCodes_UserId",
                table: "PairingCodes",
                column: "UserId");

            migrationBuilder.CreateIndex(
                name: "IX_RawConfigUploads_DeviceId",
                table: "RawConfigUploads",
                column: "DeviceId");

            migrationBuilder.CreateIndex(
                name: "IX_SaveItems_UserId_EmulatorId_Kind_GameKey_Slot_Core",
                table: "SaveItems",
                columns: new[] { "UserId", "EmulatorId", "Kind", "GameKey", "Slot", "Core" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_SaveVersions_ItemId_Seq",
                table: "SaveVersions",
                columns: new[] { "ItemId", "Seq" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_SyncGroups_UserId_EmulatorId",
                table: "SyncGroups",
                columns: new[] { "UserId", "EmulatorId" });

            migrationBuilder.CreateIndex(
                name: "IX_Users_Email",
                table: "Users",
                column: "Email",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_VaultItems_UserId_Category",
                table: "VaultItems",
                columns: new[] { "UserId", "Category" });

            migrationBuilder.CreateIndex(
                name: "IX_VaultVersions_ItemId_Seq",
                table: "VaultVersions",
                columns: new[] { "ItemId", "Seq" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_WebSessions_UserId",
                table: "WebSessions",
                column: "UserId");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "ActivityEvents");

            migrationBuilder.DropTable(
                name: "ConfigVersions");

            migrationBuilder.DropTable(
                name: "Deliveries");

            migrationBuilder.DropTable(
                name: "DeviceConfigs");

            migrationBuilder.DropTable(
                name: "DeviceEmulators");

            migrationBuilder.DropTable(
                name: "DeviceItemStates");

            migrationBuilder.DropTable(
                name: "DeviceRoots");

            migrationBuilder.DropTable(
                name: "InstallRecords");

            migrationBuilder.DropTable(
                name: "PairingCodes");

            migrationBuilder.DropTable(
                name: "RawConfigUploads");

            migrationBuilder.DropTable(
                name: "SaveVersions");

            migrationBuilder.DropTable(
                name: "ServerSettings");

            migrationBuilder.DropTable(
                name: "SyncGroups");

            migrationBuilder.DropTable(
                name: "UserBlobs");

            migrationBuilder.DropTable(
                name: "UserSettings");

            migrationBuilder.DropTable(
                name: "VaultVersions");

            migrationBuilder.DropTable(
                name: "WebSessions");

            migrationBuilder.DropTable(
                name: "ConfigBundles");

            migrationBuilder.DropTable(
                name: "Devices");

            migrationBuilder.DropTable(
                name: "SaveItems");

            migrationBuilder.DropTable(
                name: "VaultItems");

            migrationBuilder.DropTable(
                name: "Users");
        }
    }
}
