using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Tandem.Server.Data.Migrations.Sqlite
{
    /// <inheritdoc />
    public partial class AddAuditFields : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "EmulatorVersion",
                table: "SaveVersions",
                type: "TEXT",
                maxLength: 40,
                nullable: true);

            migrationBuilder.AddColumn<int>(
                name: "FileCount",
                table: "SaveVersions",
                type: "INTEGER",
                nullable: true);

            migrationBuilder.AddColumn<long>(
                name: "SilentNotifiedUtc",
                table: "Devices",
                type: "INTEGER",
                nullable: true);

            migrationBuilder.AddColumn<bool>(
                name: "Partial",
                table: "BackupRuns",
                type: "INTEGER",
                nullable: false,
                defaultValue: false);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "EmulatorVersion",
                table: "SaveVersions");

            migrationBuilder.DropColumn(
                name: "FileCount",
                table: "SaveVersions");

            migrationBuilder.DropColumn(
                name: "SilentNotifiedUtc",
                table: "Devices");

            migrationBuilder.DropColumn(
                name: "Partial",
                table: "BackupRuns");
        }
    }
}
